package dev.kauzes.mizan.risk;

import java.util.List;

/**
 * Everything the scorer is allowed to compare a payment against.
 *
 * <p>Passed in rather than looked up inside {@link Scorer}, which is what makes scoring a pure
 * function: the same payment and the same knowledge produce the same verdict, every time,
 * without a database or a clock. A test can then construct any situation directly instead of
 * arranging the world until it happens to occur.
 *
 * <p>In this story it is assembled from nothing much. MIZ-57 is where it comes from observed
 * behaviour, and the point of this shape is that the scorer will not need to change when it
 * does.
 *
 * @param typicalAmount what this merchant is usually paid, in minor units. Zero when nothing
 *     is known yet, which is a fact rather than a suspicion
 * @param recentAttemptsWithThisCard how many payments this card has made here in the last few
 *     minutes, not counting this one
 * @param cardDeclinedRecently whether this card was refused here in the recent past
 * @param cardHasPaidBefore whether this card has successfully paid this merchant before
 * @param countriesSeen the card countries this merchant has taken money from
 * @param reviewAbove the merchant's own line between approve and review
 * @param blockAbove and between review and block
 */
public record WhatWeKnow(
        long typicalAmount,
        int recentAttemptsWithThisCard,
        boolean cardDeclinedRecently,
        boolean cardHasPaidBefore,
        List<String> countriesSeen,
        int reviewAbove,
        int blockAbove) {

    /**
     * What is known about a merchant nobody has seen before.
     *
     * <p>Nothing, and that is deliberately not the same as everything being suspicious. Cold
     * start is the common case on a new platform, and a scorer that blocks the first payment
     * every merchant ever takes is a scorer nobody switches on. What it means in practice is
     * that the rules which need a baseline do not fire, and the ones that do not still work.
     */
    public static WhatWeKnow nothingYet(int reviewAbove, int blockAbove) {
        return new WhatWeKnow(0, 0, false, false, List.of(), reviewAbove, blockAbove);
    }

    public boolean hasABaseline() {
        return typicalAmount > 0;
    }

    public boolean hasSeen(String country) {
        return country == null || countriesSeen.contains(country);
    }
}
