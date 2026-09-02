package dev.kauzes.mizan.banksim;

import java.time.Instant;

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

    /** Taking an authorization that is not held is the caller's mistake, and is refused. */
    synchronized void capture() {
        requireHeld("captured");
        state = AuthorizationState.CAPTURED;
    }

    synchronized void voidIt() {
        requireHeld("voided");
        state = AuthorizationState.VOIDED;
    }

    private void requireHeld(String what) {
        if (state != AuthorizationState.HELD) {
            throw new IllegalStateException(
                    "an authorization that is " + state + " cannot be " + what);
        }
    }
}
