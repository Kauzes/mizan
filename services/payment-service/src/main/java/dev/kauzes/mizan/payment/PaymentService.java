package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.ConflictException;
import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.common.money.Money;
import dev.kauzes.mizan.payment.PaymentRequests.AuthorizeRequest;
import dev.kauzes.mizan.payment.PaymentRequests.CreatePaymentRequest;
import dev.kauzes.mizan.payment.PaymentRequests.PaymentResponse;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Starting payments, and reading where they have got to. */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final AcquirerClient acquirer;

    public PaymentService(PaymentRepository payments, AcquirerClient acquirer) {
        this.payments = payments;
        this.acquirer = acquirer;
    }

    /**
     * Asks the acquirer to reserve the money.
     *
     * <p>Nothing is posted to the ledger. An authorization is a promise that the money is
     * there, not a movement of it, and the books record movements. If a merchant facing
     * available balance ever needs to account for authorizations in flight, that is a read
     * model over payment state rather than an entry that would have to be unwound.
     *
     * <p>The acquirer is asked with the payment's own id, so asking again after a lost answer
     * returns the first decision rather than reserving the money twice.
     */
    @Transactional
    public PaymentResponse authorize(UUID merchantId, UUID paymentId, AuthorizeRequest request) {
        Payment payment = mine(merchantId, paymentId);

        // Checked before the acquirer is troubled, so a payment that cannot be authorized is
        // refused in terms of where it already is rather than after somebody else's system
        // has done work for us.
        if (!payment.status().canMoveTo(PaymentStatus.AUTHORIZED)) {
            throw new UnprocessableException(
                    "A payment that is "
                            + payment.status()
                            + " cannot be authorized"
                            + (payment.status().isFinal()
                                    ? ". That is where this payment ends."
                                    : "."));
        }

        AcquirerClient.AcquirerDecision decision = acquirer.authorize(
                payment.id(),
                payment.money().amount(),
                payment.money().currency().getCurrencyCode(),
                request.card());

        if (decision.approved()) {
            payment.authorized(decision.acquirerReference(), decision.cardLastFour());
            log.info("authorized payment {} as {}", paymentId, decision.acquirerReference());
        } else {
            payment.declined(
                    decision.acquirerReference(), decision.cardLastFour(), decision.reason());
            log.info("payment {} was declined: {}", paymentId, decision.reason());
        }

        return PaymentResponse.of(payment);
    }

    /**
     * Records that a payment is about to be attempted.
     *
     * <p>Contacts nobody and moves nothing. What this produces is something for an
     * authorization to be attached to, and something a merchant can find again by their own
     * reference if the response never arrived.
     */
    @Transactional
    public PaymentResponse create(UUID merchantId, CreatePaymentRequest request) {
        Payment payment = new Payment(
                merchantId,
                Money.of(request.amount(), currency(request.currency())),
                request.reference().trim(),
                request.description() == null ? null : request.description().trim());

        try {
            payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException taken) {
            // The reference is the only thing a caller could collide on, and it is theirs, so
            // repeating it back tells them nothing they did not just send.
            throw new ConflictException(
                    "This merchant already has a payment with that reference.");
        }

        log.info(
                "created payment {} for merchant {} as {} {} {}",
                payment.id(),
                merchantId,
                request.reference().trim(),
                request.amount(),
                request.currency());
        return PaymentResponse.of(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(UUID merchantId) {
        return payments.findByMerchantIdOrderByCreatedAtDesc(merchantId).stream()
                .map(PaymentResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponse find(UUID merchantId, UUID paymentId) {
        return PaymentResponse.of(mine(merchantId, paymentId));
    }

    Payment mine(UUID merchantId, UUID paymentId) {
        return payments
                .findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new NotFoundException("No payment with that id."));
    }

    /**
     * Three capital letters are not a currency until somebody checks. Which currency this is
     * decides what the amount means, so it is worth checking.
     */
    private static Currency currency(String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException unknown) {
            throw new UnprocessableException(code + " is not a currency this platform knows.");
        }
    }
}
