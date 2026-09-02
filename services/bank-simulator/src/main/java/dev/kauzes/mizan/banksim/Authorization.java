package dev.kauzes.mizan.banksim;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One authorization this acquirer has decided about.
 *
 * <p>Mutable in exactly one respect: an approved authorization can later be captured or
 * voided. Everything else about it is what was decided at the time.
 */
final class Authorization {

    private final String acquirerReference;
    private final String requestId;
    private final AuthorizationOutcome outcome;
    private final String reason;
    private final long amount;
    private final String currency;
    private final String cardLastFour;
    private final Instant decidedAt;

    private AuthorizationState state;

    /**
     * What has been given back, by the caller's own reference for each refund.
     *
     * <p>Keyed rather than summed, because a caller that did not hear the answer will ask
     * again and asking again must not give the money back twice. The same reason the
     * authorization itself is keyed on the request id.
     */
    private final Map<String, Long> refunds = new LinkedHashMap<>();

    Authorization(
            String acquirerReference,
            String requestId,
            AuthorizationOutcome outcome,
            String reason,
            long amount,
            String currency,
            String cardLastFour) {

        this.acquirerReference = acquirerReference;
        this.requestId = requestId;
        this.outcome = outcome;
        this.reason = reason;
        this.amount = amount;
        this.currency = currency;
        this.cardLastFour = cardLastFour;
        this.decidedAt = Instant.now();
        this.state = outcome == AuthorizationOutcome.APPROVED
                ? AuthorizationState.HELD
                : AuthorizationState.REFUSED;
    }

    String acquirerReference() {
        return acquirerReference;
    }

    String requestId() {
        return requestId;
    }

    AuthorizationOutcome outcome() {
        return outcome;
    }

    String reason() {
        return reason;
    }

    long amount() {
        return amount;
    }

    String currency() {
        return currency;
    }

    String cardLastFour() {
        return cardLastFour;
    }

    Instant decidedAt() {
        return decidedAt;
    }

    synchronized AuthorizationState state() {
        return state;
    }

    /**
     * Takes the money, or says it already has.
     *
     * <p>Repeating a capture is not an error. A caller whose answer was lost has to be able
     * to ask again, and a second capture that took the money twice would be far worse than
     * one that says the money is already taken. Capturing something that was voided, on the
     * other hand, is a real contradiction and is refused.
     */
    synchronized void capture() {
        if (state == AuthorizationState.CAPTURED) {
            return;
        }
        requireHeld("captured");
        state = AuthorizationState.CAPTURED;
    }

    synchronized void voidIt() {
        if (state == AuthorizationState.VOIDED) {
            return;
        }
        requireHeld("voided");
        state = AuthorizationState.VOIDED;
    }

    /**
     * Gives some or all of the money back.
     *
     * <p>Refuses to give back more than was taken, which is the acquirer's own arithmetic and
     * not something it trusts a caller to have got right. A platform that has the rule too is
     * a platform with the rule twice, which is the intent.
     *
     * @return the amount refunded, which for a repeat is what was refunded the first time
     */
    synchronized long refund(String reference, long amount) {
        Long already = refunds.get(reference);
        if (already != null) {
            return already;
        }

        if (state != AuthorizationState.CAPTURED) {
            throw new IllegalStateException(
                    "an authorization that is " + state + " cannot be refunded; only money "
                            + "that was taken can be given back");
        }
        if (amount <= 0 || amount > remaining()) {
            throw new IllegalStateException(
                    "cannot refund " + amount + " of " + amount() + " when " + refunded()
                            + " has already been refunded");
        }

        refunds.put(reference, amount);
        return amount;
    }

    synchronized long refunded() {
        return refunds.values().stream().mapToLong(Long::longValue).sum();
    }

    synchronized long remaining() {
        return amount - refunded();
    }

    private void requireHeld(String what) {
        if (state != AuthorizationState.HELD) {
            throw new IllegalStateException(
                    "an authorization that is " + state + " cannot be " + what);
        }
    }
}
