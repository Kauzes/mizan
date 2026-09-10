package dev.kauzes.mizan.risk;

import dev.kauzes.mizan.risk.RiskRequests.ScoreRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Decides what should happen to a payment, and says why.
 *
 * <p>A pure function of the payment and what is known about the merchant. No clock, no
 * database, no randomness: the same inputs score the same way twice, which is what makes a
 * disagreement about a decision resolvable — somebody can reproduce it.
 *
 * <p>Signals add up rather than being tested one at a time. That is the difference between a
 * scorer and a list of blocks: a payment that is slightly odd in four ways is more interesting
 * than one that is slightly odd in one, and no single rule can express that.
 */
@Component
public class Scorer {

    /**
     * How much larger than usual an amount has to be before it is worth mentioning.
     *
     * <p>Three times, because doubling is an ordinary Tuesday for most merchants and ten times
     * is too late to be useful. A number chosen here rather than derived is exactly what MIZ-57
     * replaces, and it is a constant with a comment rather than a magic number for that reason.
     */
    private static final long UNUSUAL_MULTIPLE = 3;

    /** More than this many payments from one card in the window is not a person shopping. */
    private static final int RAPID_ATTEMPTS = 3;

    public Score score(ScoreRequest request, WhatWeKnow known) {
        List<Signal> signals = new ArrayList<>();

        unusualAmount(request, known).ifPresent(signals::add);
        rapidAttempts(known).ifPresent(signals::add);
        recentlyDeclined(known).ifPresent(signals::add);
        suspiciouslyRound(request).ifPresent(signals::add);
        unfamiliarCountry(request, known).ifPresent(signals::add);
        knownGoodCard(known).ifPresent(signals::add);

        // Floored at zero. A payment cannot be safer than safe, and letting the negative rule
        // bank credit would mean a trusted card being able to absorb a genuinely alarming
        // amount — which is precisely what somebody who has stolen a trusted card would want.
        int total = Math.max(0, signals.stream().mapToInt(Signal::contribution).sum());

        return new Score(total, verdictFor(total, known), List.copyOf(signals));
    }

    /** The score, what it means, and everything that went into it. */
    public record Score(int total, Verdict verdict, List<Signal> signals) {
    }

    private static Verdict verdictFor(int total, WhatWeKnow known) {
        if (total > known.blockAbove()) {
            return Verdict.BLOCK;
        }
        if (total > known.reviewAbove()) {
            return Verdict.REVIEW;
        }
        return Verdict.APPROVE;
    }

    /**
     * Far larger than this merchant usually takes.
     *
     * <p>Silent when there is no baseline. A merchant nobody has seen before has no usual, and
     * treating that as "unusual" would make every merchant's first payment suspicious.
     */
    private static java.util.Optional<Signal> unusualAmount(
            ScoreRequest request, WhatWeKnow known) {

        if (!known.hasABaseline() || request.amount() < known.typicalAmount() * UNUSUAL_MULTIPLE) {
            return java.util.Optional.empty();
        }

        long times = request.amount() / Math.max(1, known.typicalAmount());
        return java.util.Optional.of(Signal.of(
                Rule.UNUSUAL_AMOUNT,
                Rule.UNUSUAL_AMOUNT.weight(),
                "the amount is about " + times + " times this merchant's usual of "
                        + known.typicalAmount()));
    }

    private static java.util.Optional<Signal> rapidAttempts(WhatWeKnow known) {
        if (known.recentAttemptsWithThisCard() < RAPID_ATTEMPTS) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Signal.of(
                Rule.RAPID_ATTEMPTS,
                Rule.RAPID_ATTEMPTS.weight(),
                "this card has been used " + known.recentAttemptsWithThisCard()
                        + " times here in the last few minutes"));
    }

    private static java.util.Optional<Signal> recentlyDeclined(WhatWeKnow known) {
        if (!known.cardDeclinedRecently()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Signal.of(
                Rule.RECENTLY_DECLINED,
                Rule.RECENTLY_DECLINED.weight(),
                "this card was declined by this merchant recently and is being tried again"));
    }

    /**
     * Exactly round, to the whole unit and then some.
     *
     * <p>Cheap on purpose: a thousand exactly is a perfectly ordinary thing to be charged. It
     * earns its place by combining with something else, which is the point of adding signals.
     */
    private static java.util.Optional<Signal> suspiciouslyRound(ScoreRequest request) {
        if (request.amount() % 100_000 != 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Signal.of(
                Rule.SUSPICIOUSLY_ROUND,
                Rule.SUSPICIOUSLY_ROUND.weight(),
                "the amount is exactly " + (request.amount() / 100) + " of the major unit"));
    }

    private static java.util.Optional<Signal> unfamiliarCountry(
            ScoreRequest request, WhatWeKnow known) {

        if (request.cardCountry() == null || known.countriesSeen().isEmpty()) {
            // Nothing known about where this merchant's customers come from, so anywhere is
            // as likely as anywhere else. Silence rather than suspicion.
            return java.util.Optional.empty();
        }
        if (known.hasSeen(request.cardCountry())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Signal.of(
                Rule.UNFAMILIAR_COUNTRY,
                Rule.UNFAMILIAR_COUNTRY.weight(),
                "this merchant has not been paid by a " + request.cardCountry()
                        + " card before"));
    }

    /**
     * A card that has paid this merchant before, without trouble.
     *
     * <p>The only rule that lowers a score. Without one, a scorer grows more suspicious of a
     * customer the longer they stay, which is how a platform ends up refusing its own best
     * customers on a busy day.
     */
    private static java.util.Optional<Signal> knownGoodCard(WhatWeKnow known) {
        if (!known.cardHasPaidBefore() || known.cardDeclinedRecently()) {
            // Not applied to a card that was also recently declined: the two together are a
            // stolen card being used where it had worked before, which is worse than either.
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Signal.of(
                Rule.KNOWN_GOOD_CARD,
                Rule.KNOWN_GOOD_CARD.weight(),
                "this card has paid this merchant before without trouble"));
    }
}
