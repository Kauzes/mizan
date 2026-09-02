package dev.kauzes.mizan.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * How far a refund has got, and where it may go from here.
 *
 * <p>Written down in one place for the same reason {@link PaymentStatus} is: the question
 * "can this be resumed" is asked from the request path and from the sweep, and should have one
 * answer. Each state is a real answer to "what is true right now", and each says something
 * different about where the money is — which is the only thing that matters when a process has
 * died halfway through.
 */
public enum RefundStatus {

    /**
     * The amount is reserved against the payment and the acquirer has confirmed nothing.
     *
     * <p>The money may or may not have gone back. A call that timed out looks exactly like one
     * that never happened, and nothing here guesses which: asking the acquirer again is what
     * settles it, and is safe because the refund carries the merchant's own reference.
     */
    REQUESTED,

    /**
     * The acquirer has given the money back and the books do not yet say so.
     *
     * <p>The dangerous state, and the reason this story exists. Left alone it means the ledger
     * says the platform holds money it does not.
     */
    RETURNED,

    /** The money is back and the books say so. */
    SUCCEEDED,

    /**
     * The acquirer refused outright, so nothing moved and the reservation is released.
     *
     * <p>Only for a refusal, never for a silence. "It said no" and "it said nothing" are
     * different facts and conflating them is how a merchant refunds the same money twice.
     */
    FAILED,

    /**
     * It could not be finished, and a person has to look.
     *
     * <p>The reservation is deliberately <em>not</em> released. The money may have gone back,
     * and releasing the amount would let it be refunded a second time — which is the expensive
     * direction to be wrong in, and the whole reason this state is a person's problem rather
     * than an automatic recovery.
     */
    ABANDONED;

    private static final Set<RefundStatus> NOWHERE = EnumSet.noneOf(RefundStatus.class);

    public Set<RefundStatus> next() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(RETURNED, FAILED, ABANDONED);
            case RETURNED -> EnumSet.of(SUCCEEDED, ABANDONED);
            case SUCCEEDED, FAILED, ABANDONED -> NOWHERE;
        };
    }

    public boolean canMoveTo(RefundStatus next) {
        return next().contains(next);
    }

    /** Whether anything further can happen to a refund in this state. */
    public boolean isFinished() {
        return next().isEmpty();
    }

    /** Whether something still has to happen for this refund to be done with. */
    public boolean needsFinishing() {
        return this == REQUESTED || this == RETURNED;
    }

    /**
     * Whether this state holds its reservation against the payment.
     *
     * <p>Everything but an outright refusal does. A refund nobody can finish keeps its
     * reservation precisely because nobody knows whether the money went back.
     */
    public boolean holdsItsReservation() {
        return this != FAILED;
    }
}
