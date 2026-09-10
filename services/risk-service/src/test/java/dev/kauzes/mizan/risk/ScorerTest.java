package dev.kauzes.mizan.risk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.risk.RiskRequests.ScoreRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the scorer decides, and whether it can say why.
 *
 * <p>No Spring and no database, because scoring is a pure function of a payment and what is
 * known about the merchant. That is the property worth having: any situation can be constructed
 * directly rather than arranged, and a disagreement about a decision can be reproduced by
 * somebody who was not there.
 */
class ScorerTest {

    private static final int REVIEW_ABOVE = 40;
    private static final int BLOCK_ABOVE = 70;

    private final Scorer scorer = new Scorer();

    @Test
    void anOrdinaryPaymentIsApprovedAndSaysNothing() {
        Scorer.Score score = scorer.score(payment(12345), knowing(typicalAmount(10000)));

        assertThat(score.verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(score.total()).isZero();
        assertThat(score.signals())
                .as("nothing objected, and a scorer with nothing to say should say nothing")
                .isEmpty();
    }

    @Test
    void anUnusualAmountIsWorthMentioningAndSaysHowUnusual() {
        Scorer.Score score = scorer.score(payment(90001), knowing(typicalAmount(10000)));

        assertThat(score.signals())
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.rule()).isEqualTo(Rule.UNUSUAL_AMOUNT);
                    assertThat(signal.because())
                            .as("with the actual numbers in it, so somebody can disagree with it")
                            .contains("9 times")
                            .contains("10000");
                });
        assertThat(score.verdict()).isEqualTo(Verdict.APPROVE);
    }

    @Test
    void aMerchantWithNoHistoryIsNotTreatedAsSuspicious() {
        // Cold start is the common case on a new platform. A scorer that blocks the first
        // payment every merchant ever takes is a scorer nobody switches on.
        Scorer.Score score = scorer.score(payment(5_000_000), WhatWeKnow.nothingYet(
                REVIEW_ABOVE, BLOCK_ABOVE));

        assertThat(score.signals().stream().map(Signal::rule))
                .as("no baseline means no opinion about the amount, which is a fact rather "
                        + "than a suspicion")
                .doesNotContain(Rule.UNUSUAL_AMOUNT, Rule.UNFAMILIAR_COUNTRY);
        assertThat(score.verdict()).isEqualTo(Verdict.APPROVE);
    }

    @Test
    void signalsAddUpSoThatSeveralSmallOddnessesMatter() {
        // None of these alone would hold the payment. Together they are worth a look, which is
        // the entire difference between a scorer and a list of blocks.
        WhatWeKnow known = new WhatWeKnow(
                10000, 0, false, false, List.of("TR"), REVIEW_ABOVE, BLOCK_ABOVE);

        Scorer.Score score = scorer.score(
                new ScoreRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        100_000,
                        "TRY",
                        "card_new",
                        "GB",
                        Instant.now()),
                known);

        assertThat(score.signals().stream().map(Signal::rule))
                .containsExactlyInAnyOrder(
                        Rule.UNUSUAL_AMOUNT, Rule.SUSPICIOUSLY_ROUND, Rule.UNFAMILIAR_COUNTRY);
        assertThat(score.total()).isEqualTo(30 + 5 + 20);
        assertThat(score.verdict())
                .as("55 is over this merchant's review line and under their block line")
                .isEqualTo(Verdict.REVIEW);
    }

    @Test
    void cardTestingIsBlockedRatherThanReviewed() {
        // Somebody working through a list of stolen numbers. Nobody should have to look at
        // this one to decide.
        WhatWeKnow known = new WhatWeKnow(
                10000, 6, true, false, List.of("TR"), REVIEW_ABOVE, BLOCK_ABOVE);

        Scorer.Score score = scorer.score(payment(9000), known);

        assertThat(score.total()).isEqualTo(50 + 25);
        assertThat(score.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(score.signals().stream().map(Signal::because))
                .anySatisfy(why -> assertThat(why).contains("6 times here in the last few minutes"));
    }

    @Test
    void aCardThatHasPaidBeforeLowersTheScore() {
        // Without a rule that lowers, a scorer grows more suspicious of a customer the longer
        // they stay, which is how a platform refuses its own best customers.
        WhatWeKnow stranger = new WhatWeKnow(
                10000, 0, false, false, List.of("TR"), REVIEW_ABOVE, BLOCK_ABOVE);
        WhatWeKnow regular = new WhatWeKnow(
                10000, 0, false, true, List.of("TR"), REVIEW_ABOVE, BLOCK_ABOVE);

        int asAStranger = scorer.score(payment(100_000), stranger).total();
        int asARegular = scorer.score(payment(100_000), regular).total();

        assertThat(asARegular).isLessThan(asAStranger);
        assertThat(scorer.score(payment(100_000), regular).verdict())
                .as("the same payment from a known card is not worth holding")
                .isEqualTo(Verdict.APPROVE);
    }

    @Test
    void aKnownCardThatWasAlsoJustDeclinedGetsNoCredit() {
        // A stolen card being used where it had worked before, which is worse than either fact
        // alone and must not be able to cancel itself out.
        WhatWeKnow known = new WhatWeKnow(
                10000, 0, true, true, List.of("TR"), REVIEW_ABOVE, BLOCK_ABOVE);

        Scorer.Score score = scorer.score(payment(9000), known);

        assertThat(score.signals().stream().map(Signal::rule))
                .contains(Rule.RECENTLY_DECLINED)
                .doesNotContain(Rule.KNOWN_GOOD_CARD);
    }

    @Test
    void aScoreCannotGoBelowZero() {
        // Otherwise a trusted card banks credit and can absorb a genuinely alarming amount,
        // which is exactly what somebody who has stolen a trusted card would want.
        WhatWeKnow trusted = new WhatWeKnow(
                10000, 0, false, true, List.of("TR"), REVIEW_ABOVE, BLOCK_ABOVE);

        assertThat(scorer.score(payment(9000), trusted).total()).isZero();
    }

    @Test
    void theSameRequestScoresTheSameWayTwice() {
        ScoreRequest request = payment(90001);
        WhatWeKnow known = knowing(typicalAmount(10000));

        Scorer.Score first = scorer.score(request, known);
        Scorer.Score second = scorer.score(request, known);

        assertThat(second.total()).isEqualTo(first.total());
        assertThat(second.verdict()).isEqualTo(first.verdict());
        assertThat(second.signals())
                .as("a decision nobody can reproduce is a decision nobody can argue with")
                .isEqualTo(first.signals());
    }

    @Test
    void aMerchantsOwnLinesDecideWhereTheVerdictFalls() {
        ScoreRequest request = payment(90001);

        // 30 points. Cautious merchant holds it, relaxed merchant does not, and neither is
        // wrong: a marketplace and a car dealer have different ideas of alarming.
        WhatWeKnow cautious = new WhatWeKnow(10000, 0, false, false, List.of(), 20, 50);
        WhatWeKnow relaxed = new WhatWeKnow(10000, 0, false, false, List.of(), 60, 90);

        assertThat(scorer.score(request, cautious).verdict()).isEqualTo(Verdict.REVIEW);
        assertThat(scorer.score(request, relaxed).verdict()).isEqualTo(Verdict.APPROVE);
    }

    @Test
    void scoresFastEnoughToSitInFrontOfAnAuthorization() {
        WhatWeKnow known = new WhatWeKnow(
                10000, 2, true, true, List.of("TR", "GB", "DE"), REVIEW_ABOVE, BLOCK_ABOVE);
        ScoreRequest request = payment(90001);

        // Warm it, so this measures the scorer rather than the first-call cost of everything
        // underneath it.
        for (int i = 0; i < 1000; i++) {
            scorer.score(request, known);
        }

        long startedAt = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            scorer.score(request, known);
        }
        Duration each = Duration.ofNanos((System.nanoTime() - startedAt) / 10_000);

        // Asserted rather than asserted in a comment. This is going in front of an
        // authorization in MIZ-58, and a guard that costs as much as the thing it guards has
        // stopped being a guard.
        assertThat(each)
                .as("scoring took %s each, which is too long to sit in a request path", each)
                .isLessThan(Duration.ofMillis(1));
    }

    // -- helpers ---------------------------------------------------------------------------

    private static ScoreRequest payment(long amount) {
        return new ScoreRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                amount,
                "TRY",
                "card_9f2b1c",
                "TR",
                Instant.parse("2026-09-06T10:00:00Z"));
    }

    private static long typicalAmount(long amount) {
        return amount;
    }

    private static WhatWeKnow knowing(long typicalAmount) {
        return new WhatWeKnow(
                typicalAmount, 0, false, false, List.of("TR"), REVIEW_ABOVE, BLOCK_ABOVE);
    }
}
