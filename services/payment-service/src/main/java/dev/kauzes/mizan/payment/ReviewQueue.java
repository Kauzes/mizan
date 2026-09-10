package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.error.ForbiddenException;
import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.identity.Caller;
import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.payment.PaymentRequests.PaymentResponse;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What is waiting for a person, and what happens when one decides.
 *
 * <p>Here rather than in risk-service, because the payment is here. A queue that lived beside
 * the scorer would have to reach across to release anything, and releasing a payment is a
 * payment operation that happens to have been prompted by a scorer.
 */
@Service
public class ReviewQueue {

    private static final Logger log = LoggerFactory.getLogger(ReviewQueue.class);

    private final PaymentRepository payments;
    private final PaymentEvents events;
    private final RiskClient risk;

    public ReviewQueue(PaymentRepository payments, PaymentEvents events, RiskClient risk) {
        this.payments = payments;
        this.events = events;
        this.risk = risk;
    }

    /**
     * What is held for this merchant, oldest first.
     *
     * <p>Oldest first because the oldest is the customer who has been waiting longest, and
     * because a queue worked newest-first is a queue with a permanent tail nobody reaches.
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> waiting(UUID merchantId) {
        return payments
                .findByMerchantIdAndStatusAndReviewRulingIsNullOrderByHeldAtAsc(
                        merchantId, PaymentStatus.HELD_FOR_REVIEW)
                .stream()
                .map(PaymentResponse::of)
                .toList();
    }

    /**
     * Releases a held payment so it can be authorized.
     *
     * <p>Deliberately does not authorize it here. Releasing says "the scorer was wrong about
     * this one"; taking the money is a separate act the merchant performs, through the same
     * endpoint as any other payment. Collapsing the two would mean an analyst's click charging
     * a customer, which is a larger thing than it looks.
     */
    @Transactional
    public PaymentResponse release(Caller caller, UUID paymentId, String why) {
        String who = ruledBy(caller);
        Payment payment = heldPayment(caller.merchantId(), paymentId);

        // Told to risk before anything else, so a ruling that the scorer never hears about
        // cannot happen — which is the whole feedback loop and the reason this story exists.
        tellRisk(payment, "RELEASED", who, why);

        payment.releasedForReview(who, why);

        log.info("{} released payment {} for authorization: {}", who, paymentId, why);
        return PaymentResponse.of(payment);
    }

    /** Refuses a held payment. Nobody was charged, and now nobody will be. */
    @Transactional
    public PaymentResponse refuse(Caller caller, UUID paymentId, String why) {
        String who = ruledBy(caller);
        Payment payment = heldPayment(caller.merchantId(), paymentId);

        tellRisk(payment, "REFUSED", who, why);

        payment.refusedAtReview(who);
        payment.refusedByRisk("Refused after review by " + who + ": " + why);
        events.record(payment, payment.declineReason());

        log.info("{} refused payment {}: {}", who, paymentId, why);
        return PaymentResponse.of(payment);
    }

    /**
     * Tells risk what was decided, and does not mind if it is not listening.
     *
     * <p>The same judgement as authorizing: risk is a guard, not an invariant, and an analyst
     * should not be unable to release a customer's payment because a scorer is down. What is
     * lost is one ruling's worth of learning, which is a smaller thing than a queue that
     * cannot be worked.
     */
    /**
     * Who is ruling, taken from the established identity and never from the request body.
     *
     * <p>Two things at once. A name a caller types is a name a caller can choose, so the only
     * attribution worth keeping is the one the gateway proved. And a merchant's own server may
     * not rule at all: a review exists because somebody should look, an API key is what a
     * merchant puts in a cron job, and a control a merchant can automate away is not a control.
     * That the scorer learns from these rulings makes it sharper still — a script that could
     * release its own held payments could teach this platform to stop holding them.
     */
    private static String ruledBy(Caller caller) {
        if (!caller.isPerson()) {
            throw new ForbiddenException(
                    "A held payment is released by a person, not by an API key.");
        }
        return caller.userId().toString();
    }

    private void tellRisk(Payment payment, String ruling, String who, String why) {
        risk.ruled(payment, ruling, who, why);
    }

    private Payment heldPayment(UUID merchantId, UUID paymentId) {
        Payment payment = payments
                .findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new NotFoundException("No payment with that id."));

        if (!payment.isWaitingForAPerson()) {
            // Note what this refuses: not only a payment that was never held, but one that
            // was held and has already been ruled on. Releasing leaves the payment held until
            // the merchant authorizes it, so "still held" is not the same question as "still
            // waiting", and asking the first one would let a second analyst rule again.
            throw new UnprocessableException(
                    payment.reviewRuling() == null
                            ? "A payment that is "
                                    + payment.status()
                                    + " is not waiting for anybody to rule on it."
                            : "This payment was already "
                                    + payment.reviewRuling().toLowerCase(java.util.Locale.ROOT)
                                    + " by "
                                    + payment.reviewRuledBy()
                                    + ".");
        }
        return payment;
    }
}
