package dev.kauzes.mizan.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a payment is, and where it may go next.
 *
 * <p>Written here rather than implied by the code that performs the transitions, because the
 * question "can this payment be captured" gets asked from several places and should have one
 * answer. The map exists before the journeys: most of these transitions are performed by
 * stories after this one, and they will find the rules already written down rather than
 * invent them.
 *
 * <p>A payment only ever moves forward. There is no path back to {@link #CREATED}, and the
 * three ends are ends: money that moved is corrected by a refund, which is Epic 7, not by
 * pretending it did not.
 */
public enum PaymentStatus {

    /** An intent. Recorded, nothing attempted, nobody contacted, no money anywhere. */
    CREATED,

    /** The acquirer has approved and reserved the money. Nothing has moved yet. */
    AUTHORIZED,

    /** The acquirer refused. The reason it gave is kept on the payment. */
    DECLINED,

    /** The money moved and the books say so. */
    CAPTURED,

    /** The authorization was released without being taken. Nothing moved. */
    VOIDED;

    private static final Set<PaymentStatus> NOWHERE = EnumSet.noneOf(PaymentStatus.class);

    /** Where this payment may go from here. Empty means it is finished. */
    public Set<PaymentStatus> next() {
        return switch (this) {
            case CREATED -> EnumSet.of(AUTHORIZED, DECLINED);
            case AUTHORIZED -> EnumSet.of(CAPTURED, VOIDED);
            case DECLINED, CAPTURED, VOIDED -> NOWHERE;
        };
    }

    public boolean canMoveTo(PaymentStatus next) {
        return next().contains(next);
    }

    /** Whether anything further can happen to a payment in this state. */
    public boolean isFinal() {
        return next().isEmpty();
    }
}
