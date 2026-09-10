package dev.kauzes.mizan.risk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.risk.RiskRequests.ScoreRequest;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What is normal, learned from what has happened.
 *
 * <p>MIZ-56 scored against numbers somebody chose. The point of this story is that "unusual"
 * means unusual <em>for this merchant</em>, so the tests that matter are the ones where the
 * same payment is remarkable for one merchant and unremarkable for another — and where a
 * merchant's ordinary behaviour changes around a payment that did not.
 */
@SpringBootTest
class ObservedBehaviourTest extends MizanIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");

    @Autowired
    private ObservedBehaviour observed;

    @Autowired
    private RiskService risk;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void startClean() {
        jdbc.update("delete from observed_payment");
        jdbc.update("delete from merchant_baseline");
        jdbc.update("delete from merchant_country");
        jdbc.update("delete from merchant_thresholds");
    }

    @Test
    void whatIsUnusualDependsOnTheMerchant() {
        UUID coffeeShop = UUID.randomUUID();
        UUID carDealer = UUID.randomUUID();

        takes(coffeeShop, 15, 5_00);
        takes(carDealer, 15, 500_000_00);

        // The same payment. Alarming for one, an ordinary Tuesday for the other, and no number
        // typed into a config file could be right for both.
        long amount = 100_000_00;

        assertThat(scoreOf(coffeeShop, amount).signals())
                .as("thirty times what a coffee shop takes is worth mentioning")
                .anySatisfy(signal -> assertThat(signal.rule()).isEqualTo(Rule.UNUSUAL_AMOUNT));
        assertThat(scoreOf(carDealer, amount).signals())
                .as("a fifth of what a car dealer takes is not")
                .noneSatisfy(signal -> assertThat(signal.rule()).isEqualTo(Rule.UNUSUAL_AMOUNT));
    }

    @Test
    void aBaselineFromTooFewPaymentsIsNotUsed() {
        UUID merchant = UUID.randomUUID();
        takes(merchant, 3, 5_00);

        // Three payments is a rumour, not a measure. A scorer confidently comparing against
        // one is worse than a scorer with no opinion, because it is wrong with confidence.
        assertThat(scoreOf(merchant, 100_000_00).signals())
                .noneSatisfy(signal -> assertThat(signal.rule()).isEqualTo(Rule.UNUSUAL_AMOUNT));

        takes(merchant, 10, 5_00);
        assertThat(scoreOf(merchant, 100_000_00).signals())
                .as("and once there is enough to mean something, it is used")
                .anySatisfy(signal -> assertThat(signal.rule()).isEqualTo(Rule.UNUSUAL_AMOUNT));
    }

    @Test
    void oneEnormousPaymentDoesNotRedefineWhatIsNormal() {
        UUID merchant = UUID.randomUUID();
        takes(merchant, 20, 5_00);

        // A mean would chase this and quietly move the baseline by a factor of thousands,
        // after which nothing would ever look unusual again. A median does not.
        approved(merchant, 10_000_000_00, "last4:9999", "TR", NOW);

        assertThat(typicalAmountOf(merchant))
                .as("one car sold by a coffee shop does not redefine what a coffee costs")
                .isEqualTo(5_00);
        assertThat(scoreOf(merchant, 100_000_00).signals())
                .anySatisfy(signal -> assertThat(signal.rule()).isEqualTo(Rule.UNUSUAL_AMOUNT));
    }

    @Test
    void aPaymentBecomesRemarkableWhenTheMerchantChangesAroundIt() {
        UUID merchant = UUID.randomUUID();
        long amount = 50_00;

        // Unremarkable for a merchant who takes about this much.
        takes(merchant, 20, 50_00);
        assertThat(scoreOf(merchant, amount).signals())
                .noneSatisfy(signal -> assertThat(signal.rule()).isEqualTo(Rule.UNUSUAL_AMOUNT));

        // The merchant's business changes: they now take very small payments. The same amount
        // is now the unusual one, without anybody changing a rule or a threshold.
        takes(merchant, 200, 1_00);
        assertThat(scoreOf(merchant, amount).signals())
                .as("the payment did not change; what is normal did")
                .anySatisfy(signal -> assertThat(signal.rule()).isEqualTo(Rule.UNUSUAL_AMOUNT));
    }

    @Test
    void declinedPaymentsAreRememberedAndKeptOutOfTheBaseline() {
        UUID merchant = UUID.randomUUID();
        takes(merchant, 20, 5_00);

        // A burst of refused attempts at a huge amount. Letting these into the baseline is how
        // a scorer is taught by the fraud it is meant to catch.
        for (int i = 0; i < 10; i++) {
            declined(merchant, 10_000_000_00, "last4:1111", "TR", NOW.minusSeconds(60 - i));
        }

        assertThat(typicalAmountOf(merchant))
                .as("what a merchant normally takes is what actually got taken")
                .isEqualTo(5_00);

        // But the card is remembered, which is the point of recording them at all.
        Scorer.Score score = scoreOf(merchant, 5_00, "last4:1111", "TR");
        assertThat(score.signals().stream().map(Signal::rule))
                .contains(Rule.RECENTLY_DECLINED, Rule.RAPID_ATTEMPTS);
        assertThat(score.verdict()).isEqualTo(Verdict.BLOCK);
    }

    @Test
    void aCardThatHasPaidBeforeIsRecognised() {
        UUID merchant = UUID.randomUUID();
        takes(merchant, 20, 5_00);
        approved(merchant, 5_00, "last4:4242", "TR", NOW.minus(30, ChronoUnit.DAYS));

        assertThat(scoreOf(merchant, 100_000_00, "last4:4242", "TR").signals().stream()
                        .map(Signal::rule))
                .contains(Rule.KNOWN_GOOD_CARD);
        assertThat(scoreOf(merchant, 100_000_00, "last4:0000", "TR").signals().stream()
                        .map(Signal::rule))
                .as("and a card that has not is not")
                .doesNotContain(Rule.KNOWN_GOOD_CARD);
    }

    @Test
    void oneVisitFromACountryDoesNotMakeItFamiliar() {
        UUID merchant = UUID.randomUUID();
        takes(merchant, 20, 5_00);

        // The first payment from a country teaching the platform to expect more of them is the
        // baseline being poisoned by exactly what it is meant to catch.
        approved(merchant, 5_00, "last4:1234", "RU", NOW.minusSeconds(600));
        assertThat(familiarCountriesOf(merchant)).doesNotContain("RU");

        approved(merchant, 5_00, "last4:5678", "RU", NOW.minusSeconds(500));
        assertThat(familiarCountriesOf(merchant))
                .as("twice is a pattern; once is an event")
                .contains("RU");
    }

    @Test
    void rebuildingFromTheObservedPaymentsProducesTheSameBaselines() {
        UUID merchant = UUID.randomUUID();
        takes(merchant, 25, 7_50);
        long before = typicalAmountOf(merchant);

        observed.rebuildEverything();

        // The property that makes this a projection rather than a cache with better manners.
        assertThat(typicalAmountOf(merchant)).isEqualTo(before);
    }

    @Test
    void learningFromTheSameEventTwiceLearnsOnce() {
        UUID merchant = UUID.randomUUID();
        UUID payment = UUID.randomUUID();

        observed.record(UUID.randomUUID(), payment, merchant, 5_00, "TRY",
                "last4:1234", "TR", "APPROVED", NOW);
        observed.record(UUID.randomUUID(), payment, merchant, 5_00, "TRY",
                "last4:1234", "TR", "APPROVED", NOW);

        assertThat(paymentsSeenBy(merchant))
                .as("a baseline that counted the same payment twice has quietly moved")
                .isEqualTo(1);
    }

    // -- arranging what has happened --------------------------------------------------------

    /** A merchant who has taken this many payments of about this size. */
    private void takes(UUID merchant, int howMany, long amount) {
        for (int i = 0; i < howMany; i++) {
            // Varied a little, so the median is a median of something rather than of one value
            // repeated, which is how a real merchant's amounts look.
            long varied = amount + (i % 5) - 2;
            approved(merchant, Math.max(1, varied), "last4:" + (1000 + i), "TR",
                    NOW.minus(howMany - i, ChronoUnit.DAYS));
        }
    }

    private void approved(UUID merchant, long amount, String card, String country, Instant at) {
        observed.record(UUID.randomUUID(), UUID.randomUUID(), merchant, amount, "TRY",
                card, country, "APPROVED", at);
    }

    private void declined(UUID merchant, long amount, String card, String country, Instant at) {
        observed.record(UUID.randomUUID(), UUID.randomUUID(), merchant, amount, "TRY",
                card, country, "DECLINED", at);
    }

    // -- asking -------------------------------------------------------------------------

    private Scorer.Score scoreOf(UUID merchant, long amount) {
        return scoreOf(merchant, amount, "last4:7777", "TR");
    }

    private Scorer.Score scoreOf(UUID merchant, long amount, String card, String country) {
        var response = risk.score(new ScoreRequest(
                UUID.randomUUID(), merchant, amount, "TRY", card, country, NOW));
        return new Scorer.Score(response.score(), response.verdict(), response.signals());
    }

    private long typicalAmountOf(UUID merchant) {
        Long amount = jdbc.queryForObject(
                "select typical_amount from merchant_baseline where merchant_id = ?",
                Long.class,
                merchant);
        return amount == null ? 0 : amount;
    }

    private int paymentsSeenBy(UUID merchant) {
        Integer seen = jdbc.queryForObject(
                "select payments_seen from merchant_baseline where merchant_id = ?",
                Integer.class,
                merchant);
        return seen == null ? 0 : seen;
    }

    private java.util.List<String> familiarCountriesOf(UUID merchant) {
        return jdbc.queryForList(
                "select country from merchant_country where merchant_id = ? and seen >= 2",
                String.class,
                merchant);
    }
}
