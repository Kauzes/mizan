package dev.kauzes.mizan.risk;

/**
 * The things this scorer looks for, written down in one place.
 *
 * <p>A closed set rather than a branch appearing in a method, for the same reason the payment
 * events are: each one is a claim about what makes a payment risky, and claims should be
 * visible to be argued with. Somebody reading this enum knows everything the scorer can object
 * to; somebody reading a hundred-line method knows what they had the patience for.
 *
 * <p>Each carries what it is worth. The numbers are the tuning, and having them here rather
 * than scattered through the code is what makes them tunable at all — including, later, by
 * something other than a person.
 */
public enum Rule {

    /**
     * The amount is far larger than this merchant usually takes.
     *
     * <p>The single most useful signal there is, and the one most obviously wrong when it is
     * hand set: "large" for a coffee shop is a rounding error for a car dealer. MIZ-57 replaces
     * the constant this compares against with what the merchant actually does.
     */
    UNUSUAL_AMOUNT(30, "the amount is unusual for this merchant"),

    /**
     * Several payments from one card in a very short time.
     *
     * <p>What card testing looks like: somebody with a list of stolen numbers finding out which
     * still work, in bursts, because a human buying things does not.
     *
     * <p>Weighted so that it alone is worth a person's look, and combined with anything else at
     * all it is not — it blocks. A review queue that fills with unambiguous card testing is a
     * queue whose value for the genuinely ambiguous cases is buried, and nobody should have to
     * rule on the sixth attempt from a card that was declined five minutes ago.
     */
    RAPID_ATTEMPTS(50, "several payments from this card in a very short time"),

    /** A card that has been refused here recently and is being tried again. */
    RECENTLY_DECLINED(25, "this card was declined here recently"),

    /**
     * The payment is round in a way real purchases rarely are.
     *
     * <p>Weak on its own and deliberately cheap: a thousand exactly is a perfectly ordinary
     * thing to be charged. It earns its place by combining, which is the whole point of adding
     * signals rather than testing them one at a time.
     */
    SUSPICIOUSLY_ROUND(5, "the amount is exactly round"),

    /** A card issued somewhere this merchant has never taken money from. */
    UNFAMILIAR_COUNTRY(20, "the card's country is one this merchant has not seen"),

    /**
     * A card this merchant has been paid by before, without trouble.
     *
     * <p>Negative, because a scorer that can only add is one that grows more suspicious of a
     * customer the longer they stay. Somebody who has paid the same merchant ten times is less
     * likely to be a thief than somebody who never has, and refusing to encode that is how a
     * platform ends up blocking its own best customers.
     */
    KNOWN_GOOD_CARD(-25, "this card has paid this merchant before without trouble");

    private final int weight;
    private final String describes;

    Rule(int weight, String describes) {
        this.weight = weight;
        this.describes = describes;
    }

    /** What this rule adds to a score when it fires. Negative lowers it. */
    public int weight() {
        return weight;
    }

    public String describes() {
        return describes;
    }
}
