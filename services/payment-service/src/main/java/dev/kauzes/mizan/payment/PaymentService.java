package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.ConflictException;
import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
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
    private final LedgerClient ledger;
    private final UnknownOutcomes unknownOutcomes;

    public PaymentService(
            PaymentRepository payments,
            AcquirerClient acquirer,
            LedgerClient ledger,
            UnknownOutcomes unknownOutcomes) {

        this.payments = payments;
        this.acquirer = acquirer;
        this.ledger = ledger;
        this.unknownOutcomes = unknownOutcomes;
    }

    /**
     * Takes the money, and writes down that it was taken.
     *
     * <p>Three things happen, in an order chosen for what each failure leaves behind. The
     * acquirer is asked first, because until it has taken the money there is nothing to
     * record and an entry would be a record of something that did not happen. The ledger is
     * written second. The payment is marked captured last, so the state never runs ahead of
     * the books.
     *
     * <p>Every step is repeatable, which is what makes that ordering usable rather than just
     * tidy. A capture whose answer was lost is sent again: the acquirer says the money is
     * already taken, the ledger answers with the entry it already wrote, and the payment ends
     * captured pointing at that same entry. Nothing is taken twice and nothing is recorded
     * twice.
     */
    @Transactional
    public PaymentResponse capture(UUID merchantId, UUID paymentId) {
        Payment payment = mine(merchantId, paymentId);
        refuseUnless(payment, PaymentStatus.CAPTURED, "captured");

        acquirer.capture(payment.acquirerReference());

        // If this throws, the transaction rolls back and the payment stays authorized while
        // the acquirer holds a capture. That is the honest state: the money is taken and not
        // yet recorded, the caller is told which, and sending the capture again finishes it.
        UUID entry = ledger.recordCapture(merchantId, payment);

        payment.captured(entry);
        log.info("captured payment {} and recorded it as entry {}", paymentId, entry);
        return PaymentResponse.of(payment);
    }

    /**
     * Releases the reservation, and posts nothing.
     *
     * <p>An authorization was a promise that the money was there. A void withdraws the
     * promise. No money ever moved, so the books have nothing to say, and writing an entry
     * for it would put a movement in the journal that never happened — which ADR 0012 refuses
     * for the same reason it refuses deletions.
     */
    @Transactional
    public PaymentResponse release(UUID merchantId, UUID paymentId, String because) {
        Payment payment = mine(merchantId, paymentId);
        refuseUnless(payment, PaymentStatus.VOIDED, "voided");

        acquirer.release(payment.acquirerReference());

        payment.voided(because);
        log.info("voided payment {}", paymentId);
        return PaymentResponse.of(payment);
    }

    /**
     * Refuses in terms of where the payment already is, before anybody else is troubled.
     *
     * <p>A payment that cannot be captured should not cause a capture to be attempted at the
     * acquirer and then be undone, and "that is not allowed" tells a caller nothing they can
     * act on. The same two sentences the state machine itself refuses in: where this payment
     * is, and where it could go instead.
     */
    private static void refuseUnless(Payment payment, PaymentStatus next, String verb) {
        if (!payment.status().canMoveTo(next)) {
            throw new UnprocessableException(
                    "A payment that is "
                            + payment.status()
                            + " cannot be "
                            + verb
                            + (payment.status().isFinal()
                                    ? ". That is where this payment ends."
                                    : ". It can only become " + payment.status().next() + "."));
        }
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
        refuseUnless(payment, PaymentStatus.AUTHORIZED, "authorized");

        AcquirerClient.AcquirerDecision decision;
        try {
            decision = acquirer.authorize(
                    payment.id(),
                    payment.money().amount(),
                    payment.money().currency().getCurrencyCode(),
                    request.card());
        } catch (MizanException noAnswer) {
            if (noAnswer.errorCode() == ErrorCode.UPSTREAM_TIMEOUT) {
                // Recorded in a transaction of its own, because this one is about to roll
                // back: the caller is told by an exception being thrown, and a note written
                // here would go with it. The same lesson as MIZ-33 and MIZ-36.
                unknownOutcomes.record(merchantId, paymentId, noAnswer.getMessage());
            }
            throw noAnswer;
        }

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
