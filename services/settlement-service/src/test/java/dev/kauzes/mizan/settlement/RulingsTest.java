package dev.kauzes.mizan.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A difference reaching a person, and what happens when one does.
 *
 * <p>A reconciliation nobody reads is a reconciliation that was not run. What is checked here
 * is that a difference is visible until somebody decides about it, that the decision is owned
 * and explained, that a claimed correction is checked against the books rather than believed,
 * and that deciding never moves any money.
 *
 * <p>The sweep that says all this out loud on a timer is turned off, because a test that
 * raced a scheduler would be a test about the scheduler.
 */
@SpringBootTest(
        properties = {
            "mizan.settlement.close-every=3650d",
            "mizan.reconciliation.say-first-after=3650d"
        })
class RulingsTest extends MizanIntegrationTest {

    /** A day of its own per test, for the reason {@link ReconciliationTest} gives. */
    private static final LocalDate SOMEWHERE_NOBODY_ELSE_IS =
            LocalDate.of(2210, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(50_000));

    private static final AtomicInteger DAYS = new AtomicInteger();

    private LocalDate day;

    @BeforeEach
    void aDayOfItsOwn() {
        day = SOMEWHERE_NOBODY_ELSE_IS.plusDays(DAYS.incrementAndGet());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Reconciliation reconciliation;

    @Autowired
    private Rulings rulings;

    @MockitoBean
    private AcquirerStatements statements;

    @MockitoBean
    private LedgerBooks books;

    @Test
    void putsADifferenceInFrontOfAPersonWithBothFigures() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, "acq_short", 100_00);
        theBankSays(settled("acq_short", 90_00));

        reconciliation.reconcile(day, "TRY");

        Map<String, Object> waiting = mine("acq_short");
        assertThat(waiting.get("outcome")).isEqualTo("AMOUNTS_DIFFER");
        assertThat(waiting.get("platform_amount")).isEqualTo(100_00L);
        assertThat(waiting.get("statement_amount")).isEqualTo(90_00L);
        assertThat(waiting.get("merchant_id")).isEqualTo(merchant);
        // Nobody has been asked to go and find the other number before they can start.
        assertThat(waiting.get("no_longer_reported")).isEqualTo(false);
    }

    @Test
    void takesItOutOfTheQueueOnlyWhenAPersonHasDecided() {
        captured(UUID.randomUUID(), "acq_seen", 100_00);
        theBankSays(settled("acq_seen", 90_00));
        reconciliation.reconcile(day, "TRY");

        UUID difference = (UUID) mine("acq_seen").get("id");

        Map<String, Object> ruled = rulings.rule(
                difference,
                "acknowledged",
                "ada@mizan.local",
                "the acquirer settled the authorized amount, not the captured one",
                null);

        assertThat(ruled.get("ruling")).isEqualTo("ACKNOWLEDGED");
        assertThat(ruled.get("changed").toString()).contains("nothing");
        assertThat(outstanding()).noneMatch(row -> difference.equals(row.get("id")));

        // And the decision is readable afterwards, with who made it and why.
        assertThat(rulings.about(difference))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.get("ruled_by")).isEqualTo("ada@mizan.local");
                    assertThat(record.get("why").toString()).contains("authorized amount");
                    assertThat(record.get("corrected_by")).isNull();
                });
    }

    @Test
    void refusesADecisionNobodyOwnsOrNobodyExplained() {
        UUID difference = aDifference("acq_anonymous");

        assertThatThrownBy(() -> rulings.rule(difference, "ACKNOWLEDGED", "  ", "because", null))
                .hasMessageContaining("not an audit trail");

        assertThatThrownBy(() ->
                        rulings.rule(difference, "ACKNOWLEDGED", "ada@mizan.local", "   ", null))
                .hasMessageContaining("not an audit trail");

        assertThat(outstanding()).anyMatch(row -> difference.equals(row.get("id")));
    }

    @Test
    void refusesARulingItDoesNotUnderstand() {
        UUID difference = aDifference("acq_written_off");

        // Notably there is no WRITTEN_OFF. An automatic write-off is the feature that makes a
        // ledger untrustworthy, and a manual one by another name is the same feature.
        assertThatThrownBy(() -> rulings.rule(
                        difference, "WRITTEN_OFF", "ada@mizan.local", "it is only a kurus", null))
                .hasMessageContaining("ACKNOWLEDGED");
    }

    @Test
    void refusesACorrectionThatNamesNoEntry() {
        UUID difference = aDifference("acq_unnamed");

        assertThatThrownBy(() -> rulings.rule(
                        difference, "CORRECTED", "ada@mizan.local", "posted a correction", null))
                .hasMessageContaining("names the entry that made it");
    }

    @Test
    void refusesACorrectionTheBooksHaveNeverHeardOf() {
        UUID difference = aDifference("acq_imaginary");
        UUID imaginary = UUID.randomUUID();
        Mockito.when(books.entry(imaginary)).thenReturn(Optional.empty());

        // A decision recorded as evidence has to be evidence. "Corrected by entry 8f21…" is
        // only evidence if that entry is there.
        assertThatThrownBy(() -> rulings.rule(
                        difference, "CORRECTED", "ada@mizan.local", "posted it", imaginary))
                .hasMessageContaining("no entry");

        assertThat(rulings.about(difference)).isEmpty();
    }

    @Test
    void refusesACorrectionInSomebodyElsesBooks() {
        UUID merchant = UUID.randomUUID();
        UUID difference = aDifference("acq_elsewhere", merchant);
        UUID entry = UUID.randomUUID();
        Mockito.when(books.entry(entry))
                .thenReturn(Optional.of(new LedgerBooks.PostedEntry(
                        entry, UUID.randomUUID(), "A correction of something else", null)));

        assertThatThrownBy(() -> rulings.rule(
                        difference, "CORRECTED", "ada@mizan.local", "posted it", entry))
                .hasMessageContaining("not in this merchant's books");
    }

    @Test
    void recordsACorrectionAndTheEntryThatMadeIt() {
        UUID merchant = UUID.randomUUID();
        UUID payment = captured(merchant, "acq_fixed", 100_00);
        theBankSays(settled("acq_fixed", 90_00));
        reconciliation.reconcile(day, "TRY");

        UUID difference = (UUID) mine("acq_fixed").get("id");
        UUID entry = UUID.randomUUID();
        Mockito.when(books.entry(entry))
                .thenReturn(Optional.of(new LedgerBooks.PostedEntry(
                        entry, merchant, "Correcting a settlement difference", null)));

        Map<String, Object> ruled = rulings.rule(
                difference, "CORRECTED", "ada@mizan.local", "the ten lira never arrived", entry);

        assertThat(ruled.get("correctedBy")).isEqualTo(entry);
        assertThat(rulings.about(difference).getFirst().get("corrected_by")).isEqualTo(entry);

        // And ruling moved nothing itself. The entry named above is what moved the money, in
        // the ledger, where it is an entry like any other.
        Mockito.verify(books, Mockito.never())
                .recordFee(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any());
        Mockito.verify(books, Mockito.never())
                .recordPayout(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any());
        assertThat(jdbc.queryForObject(
                        "select amount from settleable where payment_id = ?", Long.class, payment))
                .isEqualTo(100_00L);
    }

    @Test
    void keepsADifferenceALaterRunNoLongerReports() {
        captured(UUID.randomUUID(), "acq_vanishing", 100_00);
        theBankSays(settled("acq_vanishing", 90_00));
        reconciliation.reconcile(day, "TRY");

        // The bank sends a statement that agrees this time, so nothing is found.
        theBankSays(settled("acq_vanishing", 100_00));
        reconciliation.reconcile(day, "TRY");

        // Still waiting for a person, and now saying that the newest run did not see it. A
        // difference that stopped being reported because a later run did not notice it is a
        // difference nobody decided about, and a queue that quietly forgot it would be a queue
        // that hides exactly the case worth looking at.
        Map<String, Object> waiting = mine("acq_vanishing");
        assertThat(waiting.get("no_longer_reported")).isEqualTo(true);
    }

    @Test
    void keepsEveryRulingAboutTheSameDifference() {
        UUID merchant = UUID.randomUUID();
        UUID difference = aDifference("acq_twice", merchant);
        UUID entry = UUID.randomUUID();
        Mockito.when(books.entry(entry))
                .thenReturn(Optional.of(new LedgerBooks.PostedEntry(
                        entry, merchant, "Correcting a settlement difference", null)));

        rulings.rule(difference, "ACKNOWLEDGED", "ada@mizan.local", "asking the bank", null);
        rulings.rule(difference, "CORRECTED", "ada@mizan.local", "they agreed", entry);

        // Somebody who acknowledged this on Monday and corrected it on Thursday did two
        // things, and a record that kept only the second is a record of a decision nobody took.
        assertThat(rulings.about(difference)).hasSize(2);
        assertThat(rulings.about(difference).getFirst().get("ruling")).isEqualTo("ACKNOWLEDGED");
        assertThat(rulings.about(difference).getLast().get("ruling")).isEqualTo("CORRECTED");
    }

    @Test
    void aRulingCannotBeRewrittenOrDeleted() {
        UUID difference = aDifference("acq_permanent");
        rulings.rule(difference, "ACKNOWLEDGED", "ada@mizan.local", "the bank is right", null);

        assertThatThrownBy(() -> jdbc.update(
                        "update reconciliation_ruling set why = 'something else' "
                                + "where difference_id = ?",
                        difference))
                .hasMessageContaining("cannot be changed");

        assertThatThrownBy(() -> jdbc.update(
                        "delete from reconciliation_ruling where difference_id = ?", difference))
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void refusesToRuleOnADifferenceThatIsNotThere() {
        assertThatThrownBy(() -> rulings.rule(
                        UUID.randomUUID(), "ACKNOWLEDGED", "ada@mizan.local", "no idea", null))
                .hasMessageContaining("No reconciliation difference");
    }

    @Test
    void saysHowMuchIsWaitingAndSinceWhen() {
        aDifference("acq_counted");

        Map<String, Object> waiting = rulings.howMuchIsWaiting();

        // Counted rather than taken from the list, because a queue that says "500" when it
        // holds nine thousand is worse than no number at all.
        assertThat(((Number) waiting.get("outstanding")).longValue()).isPositive();
        assertThat(waiting.get("oldest")).isNotNull();
        assertThat(waiting.get("byOutcome").toString()).contains("AMOUNTS_DIFFER");
    }

    // -- helpers ---------------------------------------------------------------------------

    /** One difference of this test's own, already found and waiting for somebody. */
    private UUID aDifference(String reference) {
        return aDifference(reference, UUID.randomUUID());
    }

    private UUID aDifference(String reference, UUID merchant) {
        captured(merchant, reference, 100_00);
        theBankSays(settled(reference, 90_00));
        reconciliation.reconcile(day, "TRY");
        return (UUID) mine(reference).get("id");
    }

    /**
     * This test's own difference out of everything the queue holds.
     *
     * <p>Found by the reference alone, which is enough because every reference here carries
     * the day it belongs to: the queue is everything nobody has ruled on, including what other
     * tests and last week's run left waiting.
     */
    private Map<String, Object> mine(String reference) {
        String named = named(reference);
        return outstanding().stream()
                .filter(row -> named.equals(row.get("acquirer_reference")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(named + " is not waiting for anybody"));
    }

    /** A reference nothing else will have used, because it says which day it is about. */
    private String named(String reference) {
        return reference + "_" + day;
    }

    private List<Map<String, Object>> outstanding() {
        return rulings.outstanding(500);
    }

    private void theBankSays(AcquirerStatements.Settled... settled) {
        List<AcquirerStatements.Settled> rows = List.of(settled);
        Mockito.when(statements.forDay(day, "TRY"))
                .thenReturn(new AcquirerStatements.Statement(
                        day,
                        "TRY",
                        rows,
                        rows.size(),
                        rows.stream().mapToLong(AcquirerStatements.Settled::amount).sum()));
    }

    private AcquirerStatements.Settled settled(String reference, long amount) {
        return new AcquirerStatements.Settled(named(reference), amount, "TRY");
    }

    private UUID captured(UUID merchant, String acquirerReference, long amount) {
        UUID payment = UUID.randomUUID();
        jdbc.update(
                """
                insert into settleable (payment_id, merchant_id, amount, currency,
                    captured_at, settled_for, acquirer_reference)
                values (?, ?, ?, 'TRY', ?, ?, ?)
                """,
                payment,
                merchant,
                amount,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                day,
                named(acquirerReference));
        return payment;
    }
}
