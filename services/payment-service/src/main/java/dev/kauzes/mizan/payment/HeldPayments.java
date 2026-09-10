package dev.kauzes.mizan.payment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payments held for a person, and what happens when nobody comes.
 *
 * <p>A payment held forever is a customer who was neither charged nor told why, and a merchant
 * who cannot tell a slow review from a lost one. So a hold has a deadline, and reaching it is a
 * decision with a reason rather than a silent state change.
 *
 * <p>It expires to declined rather than to authorized. Holding a payment is the platform saying
 * it is not sure; letting that resolve to "take the money" when nobody looked would make the
 * safe-looking answer the one that happens by default, which is how a review queue becomes a
 * delay before approving everything.
 */
@Component
public class HeldPayments {

    private static final Logger log = LoggerFactory.getLogger(HeldPayments.class);

    private final PaymentRepository payments;
    private final PaymentEvents events;
    private final TransactionTemplate transaction;
    private final Duration holdFor;

    public HeldPayments(
            PaymentRepository payments,
            PaymentEvents events,
            PlatformTransactionManager transactions,
            @Value("${mizan.risk.hold-for:24h}") Duration holdFor) {

        this.payments = payments;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
        this.holdFor = holdFor;
    }

    @Scheduled(fixedDelayString = "${mizan.risk.expire-every:5m}")
    public void expireWhatNobodyRuledOn() {
        Instant heldBefore = Instant.now().minus(holdFor);
        List<Payment> stale = payments.findByStatusAndReviewRulingIsNullAndHeldAtBefore(
                PaymentStatus.HELD_FOR_REVIEW, heldBefore);

        if (stale.isEmpty()) {
            return;
        }

        // At warn, because a payment expiring is a review that did not happen. One is an
        // oversight; a hundred is a staffing problem, and neither should be found out about
        // from a customer.
        log.warn("{} payment(s) were held for review and nobody ruled on them", stale.size());
        stale.forEach(payment -> expire(payment.id(), payment.merchantId()));
    }

    private void expire(java.util.UUID paymentId, java.util.UUID merchantId) {
        transaction.executeWithoutResult(status -> {
            Payment payment = payments.findByIdAndMerchantId(paymentId, merchantId).orElseThrow();
            if (!payment.isWaitingForAPerson()) {
                // An analyst ruled between the query and here. Theirs wins: expiring a payment
                // somebody has already released would overrule them by doing nothing, which is
                // the worst way to be overruled.
                return;
            }

            payment.refusedByRisk(
                    "This payment was held for review and expired after "
                            + holdFor
                            + " without anybody ruling on it. Nothing was charged.");
            events.record(payment, payment.declineReason());
            log.info("payment {} expired without a ruling", paymentId);
        });
    }
}
