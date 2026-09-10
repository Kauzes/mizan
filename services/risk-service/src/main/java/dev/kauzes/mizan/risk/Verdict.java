package dev.kauzes.mizan.risk;

/**
 * What risk thinks should happen to a payment.
 *
 * <p>Three answers, not two, and the middle one is the reason this service exists. A scorer
 * that can only approve or block has to be certain about everything, so it is tuned either to
 * let fraud through or to refuse honest customers — and both of those are decisions somebody
 * made by choosing a threshold, without meaning to.
 *
 * <p>{@link #REVIEW} is the honest answer for a payment that is unusual and not obviously
 * wrong: hold it, charge nobody, and let a person decide. It costs a delay, which is a real
 * cost and a smaller one than either mistake it replaces.
 */
public enum Verdict {

    /** Nothing here is worth stopping for. Carry on to the acquirer. */
    APPROVE,

    /**
     * Unusual enough to want a person, not wrong enough to refuse.
     *
     * <p>The payment is held and nobody is charged. A held payment is not a failed payment,
     * and a merchant has to be able to tell the difference.
     */
    REVIEW,

    /** Wrong enough that nobody should be asked to look at it. Refuse without contacting anybody. */
    BLOCK
}
