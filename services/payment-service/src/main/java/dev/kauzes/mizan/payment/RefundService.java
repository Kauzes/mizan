package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.payment.PaymentRequests.RefundRequest;
import dev.kauzes.mizan.payment.PaymentRequests.RefundResponse;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Giving money back. */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final AcquirerClient acquirer;
    private final LedgerClient ledger;
    private final PaymentEvents events;

    public RefundService(
            PaymentRepository payments,
            RefundRepository refunds,
            AcquirerClient acquirer,
            LedgerClient ledger,
            PaymentEvents events) {

        this.payments = payments;
        this.refunds = refunds;
        this.acquirer = acquirer;
        this.ledger = ledger;
        this.events = events;
    }

    /**
     * Gives money back, in full or in part.
     *
     * <p>The same order as a capture and for the same reasons: the acquirer first, because
     * until it has given the money back there is nothing to record; the ledger second, so the
     * books never say less than what happened; the payment's own total last, so it never runs
     * ahead of the entry that justifies it.
     *
     * <p>The payment row is locked before anything is decided. Two refunds arriving at once
     * must not each read the same remaining amount and both conclude there is room — a limit
     * is only a limit if reading it and writing it are one thing. MIZ-39 learned that the
     * expensive way, with optimistic retries that turned out to cap how many callers an
     * account could have.
     */
    @Transactional
    public RefundResponse refund(UUID merchantId, UUID paymentId, RefundRequest request) {
        String reference = request.reference().trim();

        // Locked first, and everything decided inside that lock. Two refunds arriving at once
        // must not each read the same remaining amount and both conclude there is room: a
        // limit is only a limit if reading it and writing it are one thing. It also serialises
        // the duplicate check below, so two copies of one retry cannot both find nothing.
        Payment payment = payments
                .findForUpdateByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new NotFoundException("No payment with that id."));

        // A caller who never heard the first answer is asking the same question again, not a
        // new one, and is given the refund that was already made.
        var already = refunds.findByPaymentIdAndReference(paymentId, reference);
        if (already.isPresent()) {
            log.info("refund {} of payment {} was already made", reference, paymentId);
            return RefundResponse.of(already.get());
        }

        requireSameCurrency(payment, request);

        // Checked before the acquirer is troubled, and refused in the caller's own terms
        // rather than leaving them to interpret somebody else's message.
        payment.refunded(request.amount());

        // The acquirer first, because until it has given the money back there is nothing to
        // record. It is keyed on the merchant's own reference, so a call whose answer was lost
        // can be repeated and gives the money back once.
        AcquirerClient.AcquirerRefund given =
                acquirer.refund(payment.acquirerReference(), reference, request.amount());

        // The ledger second, so the books never say less than what happened. If this throws,
        // the whole transaction rolls back: the acquirer has given the money back and this
        // platform has not recorded it, the caller is told which, and sending the refund again
        // finishes it. MIZ-52 is what stops a caller having to.
        UUID entry = ledger.recordRefund(merchantId, payment, request.amount(), reference);

        Refund refund = new Refund(
                payment,
                request.amount(),
                reference,
                request.reason(),
                given.acquirerReference(),
                entry);
        refunds.saveAndFlush(refund);

        events.recordRefund(payment, refund);

        log.info(
                "refunded {} of payment {} as {} and recorded it as entry {}",
                request.amount(),
                paymentId,
                given.acquirerReference(),
                entry);
        return RefundResponse.of(refund);
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> list(UUID merchantId, UUID paymentId) {
        mine(merchantId, paymentId);
        return refunds.findByPaymentIdOrderByCreatedAtDesc(paymentId).stream()
                .map(RefundResponse::of)
                .toList();
    }

    /**
     * A refund in a currency the payment was never in is a mistake, not a conversion.
     *
     * <p>Refusing it is the only safe answer: this platform has no rate, and inventing one to
     * be helpful is how a refund gives back a different amount of money than was taken.
     */
    private static void requireSameCurrency(Payment payment, RefundRequest request) {
        if (request.currency() == null) {
            return;
        }
        Currency asked;
        try {
            asked = Currency.getInstance(request.currency().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new UnprocessableException(
                    request.currency() + " is not a currency this platform knows.");
        }
        if (!asked.equals(payment.money().currency())) {
            throw new UnprocessableException(
                    "This payment was taken in "
                            + payment.money().currency().getCurrencyCode()
                            + " and can only be refunded in "
                            + payment.money().currency().getCurrencyCode()
                            + ".");
        }
    }

    private Payment mine(UUID merchantId, UUID paymentId) {
        return payments
                .findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new NotFoundException("No payment with that id."));
    }
}
