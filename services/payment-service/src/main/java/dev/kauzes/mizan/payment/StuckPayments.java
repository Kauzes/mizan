package dev.kauzes.mizan.payment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What is stuck, and why.
 *
 * <p>There are several ways for a payment to end up somewhere no amount of retrying will move
 * it: an authorization the acquirer has no record of, a refund whose saga gave up. Each is
 * handled correctly on its own and each is invisible unless somebody goes looking in a
 * different place for it. Three stories left one of these behind each, and every one of them
 * pointed here.
 *
 * <p>Deliberately built last. Each of those stories could have grown its own operator view and
 * they would have been three views of the same question; the shape is only obvious once all the
 * kinds of stuck exist.
 */
@Service
public class StuckPayments {

    private static final Logger log = LoggerFactory.getLogger(StuckPayments.class);

    /**
     * One thing that needs a person.
     *
     * @param whatWeBelieve what this platform thinks is true
     * @param whatTheAcquirerBelieves what the acquirer says, asked live. Comparing the two is
     *     the first thing anybody does, so it is done here rather than left as an exercise
     */
    public record Stuck(
            String kind,
            UUID id,
            UUID merchantId,
            UUID paymentId,
            String reference,
            long amount,
            String currency,
            String whatWeBelieve,
            Object whatTheAcquirerBelieves,
            String reason,
            String correlationId,
            int attempts,
            Instant since) {
    }

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final AcquirerClient acquirer;
    private final OperatorDecisions decisions;

    public StuckPayments(
            PaymentRepository payments,
            RefundRepository refunds,
            AcquirerClient acquirer,
            OperatorDecisions decisions) {

        this.payments = payments;
        this.refunds = refunds;
        this.acquirer = acquirer;
        this.decisions = decisions;
    }

    /** Everything that needs a person, of every kind, in one answer. */
    @Transactional(readOnly = true)
    public List<Stuck> everything() {
        List<Stuck> stuck = new ArrayList<>();

        for (Payment payment : payments.findByNeedsAttentionSinceIsNotNullOrderByNeedsAttentionSinceAsc()) {
            stuck.add(new Stuck(
                    "PAYMENT",
                    payment.id(),
                    payment.merchantId(),
                    payment.id(),
                    payment.reference(),
                    payment.money().amount(),
                    payment.money().currency().getCurrencyCode(),
                    payment.status().name(),
                    whatTheAcquirerSaysAbout(payment.id()),
                    payment.attentionReason(),
                    correlationOf(payment),
                    payment.resolveAttempts(),
                    payment.needsAttentionSince()));
        }

        for (Refund refund : refunds.findByStatusAndAttentionHandledAtIsNullOrderByUpdatedAtDesc(
                RefundStatus.ABANDONED)) {
            stuck.add(new Stuck(
                    "REFUND",
                    refund.id(),
                    refund.merchantId(),
                    refund.paymentId(),
                    refund.reference(),
                    refund.amount(),
                    refund.currency(),
                    refund.status().name(),
                    // Asked by the payment, because that is the identifier the acquirer knows
                    // this money by, and a refund the acquirer never heard of has no id there.
                    whatTheAcquirerSaysAbout(refund.paymentId()),
                    refund.lastError(),
                    null,
                    refund.attempts(),
                    refund.updatedAt()));
        }

        return stuck;
    }

    /**
     * What the acquirer says about this payment, right now.
     *
     * <p>Asked live rather than remembered, because the whole reason somebody is looking at
     * this list is that the platform's own record is not to be trusted. A failure to ask is
     * itself worth showing: "the acquirer cannot be reached" is a different problem from "the
     * acquirer has no record", and an operator should not have to guess which they have.
     */
    private Object whatTheAcquirerSaysAbout(UUID paymentId) {
        try {
            Optional<AcquirerClient.AcquirerDecision> decided = acquirer.lookUp(paymentId);
            return decided
                    .<Object>map(decision -> Map.of(
                            "outcome", decision.approved() ? "APPROVED" : "DECLINED",
                            "acquirerReference", String.valueOf(decision.acquirerReference()),
                            "reason", String.valueOf(decision.reason())))
                    .orElse("no record of this payment");
        } catch (RuntimeException couldNotAsk) {
            log.warn("could not ask the acquirer about {}", paymentId, couldNotAsk);
            return "could not be asked: " + couldNotAsk.getMessage();
        }
    }

    /** The request that first put this payment in doubt, if its history still knows. */
    private static String correlationOf(Payment payment) {
        return payment.history().stream()
                .filter(step -> step.to() == PaymentStatus.AUTHORIZATION_UNKNOWN)
                .findFirst()
                .map(PaymentTransition::reason)
                .orElse(null);
    }

    /**
     * Puts something back in front of the sweep.
     *
     * <p>The attempts are reset, because the reason a person is retrying is that something has
     * changed and the old count is a record of a world that no longer exists.
     */
    @Transactional
    public Map<String, Object> retry(String kind, UUID id, String who, String why) {
        return switch (kind) {
            case "PAYMENT" -> {
                Payment payment = payments.findById(id).orElseThrow();
                payment.attentionHandled();
                yield decided(payment.merchantId(), kind, id, "RETRY", who, why,
                        "the payment is being resolved again from attempt zero");
            }
            case "REFUND" -> {
                Refund refund = refunds.findById(id).orElseThrow();
                refund.tryAgain();
                yield decided(refund.merchantId(), kind, id, "RETRY", who, why,
                        "the refund is being finished again from attempt zero");
            }
            default -> throw new dev.kauzes.mizan.common.error.UnprocessableException(
                    kind + " is not something that gets stuck.");
        };
    }

    /**
     * Records that a person has dealt with it, so it stops appearing.
     *
     * <p>Nothing about the money changes. This says a human has looked and decided, which is a
     * different and more honest thing than a platform pretending it worked it out.
     */
    @Transactional
    public Map<String, Object> close(String kind, UUID id, String who, String why) {
        return switch (kind) {
            case "PAYMENT" -> {
                Payment payment = payments.findById(id).orElseThrow();
                payment.attentionHandled();
                yield decided(payment.merchantId(), kind, id, "CLOSED", who, why,
                        "the payment no longer needs attention; its state is unchanged");
            }
            case "REFUND" -> {
                Refund refund = refunds.findById(id).orElseThrow();
                refund.closedByAPerson();
                yield decided(refund.merchantId(), kind, id, "CLOSED", who, why,
                        "the refund no longer needs attention; its reservation is unchanged");
            }
            default -> throw new dev.kauzes.mizan.common.error.UnprocessableException(
                    kind + " is not something that gets stuck.");
        };
    }

    private Map<String, Object> decided(
            UUID merchantId,
            String kind,
            UUID id,
            String decision,
            String who,
            String why,
            String changed) {

        decisions.record(merchantId, kind, id, decision, who, why, changed);
        log.warn("{} {} was {} by {}: {}", kind, id, decision, who, why);
        return Map.of("kind", kind, "id", id, "decision", decision, "changed", changed);
    }

    /** What a person has decided about one thing, oldest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> historyOf(String kind, UUID id) {
        return decisions.about(kind, id);
    }
}
