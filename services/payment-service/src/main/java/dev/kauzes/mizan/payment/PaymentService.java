package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.ConflictException;
import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.common.money.Money;
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

    public PaymentService(PaymentRepository payments) {
        this.payments = payments;
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
