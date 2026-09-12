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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Comparing what this platform believes with what the bank says.
 *
 * <p>The statement is handed to the reconciler rather than fetched over HTTP, because what is
 * being tested is the comparison: which four answers it gives, whether it gives the same ones
 * twice, and what it does with a capture it cannot match at all. The reading of somebody
 * else's file has its own tests, and the bank's own disagreements have theirs.
 */
@SpringBootTest(properties = "mizan.settlement.close-every=3650d")
class ReconciliationTest extends MizanIntegrationTest {

    /**
     * A day of its own per test, and a different set of days on every run.
     *
     * <p>Everything here is keyed by the day being reconciled, and the database outlives a
     * single run: the container holding it is shared, and a database is created only if it is
     * not there already. So a fixed date would make each test's assertions depend both on
     * which tests ran before it and on what last week's run left behind. A day each, from a
     * base nobody else will pick, is cheaper than cleaning up and says what it means.
     */
    private static final LocalDate SOMEWHERE_NOBODY_ELSE_IS = LocalDate.of(2200, 1, 1)
            .plusDays(java.util.concurrent.ThreadLocalRandom.current().nextInt(50_000));

    private static final java.util.concurrent.atomic.AtomicInteger DAYS =
            new java.util.concurrent.atomic.AtomicInteger();

    private LocalDate day;

    @org.junit.jupiter.api.BeforeEach
    void aDayOfItsOwn() {
        day = SOMEWHERE_NOBODY_ELSE_IS.plusDays(DAYS.incrementAndGet());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Reconciliation reconciliation;

    @MockitoBean
    private AcquirerStatements statements;

    @Test
    void mattersMatchWhenBothSidesAgree() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, "acq_1", 100_00);
        captured(merchant, "acq_2", 200_00);
        theBankSays(settled("acq_1", 100_00), settled("acq_2", 200_00));

        Reconciliation.Found found = reconciliation.reconcile(day, "TRY");

        assertThat(found.matched()).isEqualTo(2);
        assertThat(found.missingFromStatement()).isZero();
        assertThat(found.extraOnStatement()).isZero();
        assertThat(found.amountsDiffer()).isZero();
        // Worth asserting: a job that reports differences where there are none is as useless
        // as one that finds none where there are.
        assertThat(outstanding(day)).isEmpty();
    }

    @Test
    void namesWhatIsMissingFromTheStatement() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, "acq_missing", 100_00);
        theBankSays();

        Reconciliation.Found found = reconciliation.reconcile(day, "TRY");

        assertThat(found.missingFromStatement()).isEqualTo(1);
        Map<String, Object> difference = onlyOutstanding(day);
        assertThat(difference.get("outcome")).isEqualTo("MISSING_FROM_STATEMENT");
        assertThat(difference.get("acquirer_reference")).isEqualTo("acq_missing");
        // Both figures, so somebody can act rather than go looking. Nothing on the bank's
        // side, because there is nothing there.
        assertThat(((Number) difference.get("platform_amount")).longValue()).isEqualTo(100_00L);
        assertThat(difference.get("statement_amount")).isNull();
        assertThat(difference.get("merchant_id")).isEqualTo(merchant);
    }

    @Test
    void namesWhatIsOnTheStatementAndNowhereElse() {
        theBankSays(settled("acq_never_issued", 42_00));

        Reconciliation.Found found = reconciliation.reconcile(day, "TRY");

        assertThat(found.extraOnStatement()).isEqualTo(1);
        Map<String, Object> difference = onlyOutstanding(day);
        assertThat(difference.get("outcome")).isEqualTo("EXTRA_ON_STATEMENT");
        assertThat(((Number) difference.get("statement_amount")).longValue()).isEqualTo(42_00L);
        // Nothing on this side to name, and the row says so rather than guessing.
        assertThat(difference.get("platform_amount")).isNull();
        assertThat(difference.get("payment_id")).isNull();
    }

    @Test
    void saysByHowMuchTwoSidesDisagree() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, "acq_off", 100_00);
        // One minor unit, which is the difference a job is most likely to round away.
        theBankSays(settled("acq_off", 100_00 - 1));

        Reconciliation.Found found = reconciliation.reconcile(day, "TRY");

        assertThat(found.amountsDiffer()).isEqualTo(1);
        assertThat(found.matched()).isZero();

        Map<String, Object> difference = onlyOutstanding(day);
        assertThat(difference.get("outcome")).isEqualTo("AMOUNTS_DIFFER");
        assertThat(((Number) difference.get("platform_amount")).longValue()).isEqualTo(100_00L);
        assertThat(((Number) difference.get("statement_amount")).longValue())
                .isEqualTo(100_00L - 1);
    }

    @Test
    void saysWhenACaptureCannotBeMatchedAtAll() {
        UUID merchant = UUID.randomUUID();
        UUID payment = captured(merchant, null, 100_00);
        theBankSays();

        Reconciliation.Found found = reconciliation.reconcile(day, "TRY");

        // The answer the story's four did not cover. Calling this missing would send somebody
        // looking for it in a file where it could never appear.
        assertThat(found.unmatchable()).isEqualTo(1);
        assertThat(found.missingFromStatement()).isZero();

        Map<String, Object> difference = onlyOutstanding(day);
        assertThat(difference.get("outcome")).isEqualTo("UNMATCHABLE");
        assertThat(difference.get("payment_id")).isEqualTo(payment);
    }

    @Test
    void findsAllFourAtOnceAndKeepsThemApart() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, "acq_fine", 100_00);
        captured(merchant, "acq_gone", 200_00);
        captured(merchant, "acq_off", 300_00);
        theBankSays(
                settled("acq_fine", 100_00),
                settled("acq_off", 300_00 - 1),
                settled("acq_phantom", 42_00));

        Reconciliation.Found found = reconciliation.reconcile(day, "TRY");

        // A single "failed" count would hide every one of these.
        assertThat(found.matched()).isEqualTo(1);
        assertThat(found.missingFromStatement()).isEqualTo(1);
        assertThat(found.amountsDiffer()).isEqualTo(1);
        assertThat(found.extraOnStatement()).isEqualTo(1);
        assertThat(outstanding(day)).hasSize(3);
    }

    @Test
    void saysTheSameThingTwice() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, "acq_gone", 100_00);
        captured(merchant, "acq_off", 200_00);
        theBankSays(settled("acq_off", 200_00 - 1), settled("acq_phantom", 42_00));

        Reconciliation.Found first = reconciliation.reconcile(day, "TRY");
        Reconciliation.Found again = reconciliation.reconcile(day, "TRY");

        // The only time anybody reconciles twice is after an incident, which is the worst
        // time to be handed a page of problems that all look brand new.
        assertThat(again.asAnswer())
                .containsAllEntriesOf(Map.of(
                        "matched", first.matched(),
                        "missingFromStatement", first.missingFromStatement(),
                        "extraOnStatement", first.extraOnStatement(),
                        "amountsDiffer", first.amountsDiffer()));

        assertThat(outstanding(day))
                .as("the same three problems, not six")
                .hasSize(3);
        assertThat(reconciliation.runsFor(day))
                .as("and two runs, because both happened")
                .hasSize(2);
    }

    @Test
    void keepsWhenADifferenceWasFirstSeen() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, "acq_gone", 100_00);
        theBankSays();

        reconciliation.reconcile(day, "TRY");
        Instant firstSeen = seenAt(day, "first_seen_at");

        reconciliation.reconcile(day, "TRY");

        // A difference found again is the same difference. Its age is what tells somebody it
        // has been outstanding for a week.
        assertThat(seenAt(day, "first_seen_at")).isEqualTo(firstSeen);
        assertThat(seenAt(day, "last_seen_at")).isAfterOrEqualTo(firstSeen);
    }

    @Test
    void recordsWhatTheStatementItselfClaimed() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, "acq_1", 100_00);
        theBankSays(settled("acq_1", 100_00));

        Reconciliation.Found found = reconciliation.reconcile(day, "TRY");

        // A statement is a file somebody else sent and cannot be asked for again in the state
        // it arrived in. A run that did not keep its figures cannot be audited afterwards.
        Map<String, Object> run = jdbc.queryForMap(
                "select statement_rows, statement_total from reconciliation_run where id = ?",
                found.runId());

        assertThat(((Number) run.get("statement_rows")).intValue()).isEqualTo(1);
        assertThat(((Number) run.get("statement_total")).longValue()).isEqualTo(100_00L);
    }

    @Test
    void aRunCannotBeRewritten() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, "acq_1", 100_00);
        theBankSays(settled("acq_1", 100_00));
        Reconciliation.Found found = reconciliation.reconcile(day, "TRY");

        // A run records what was found. Evidence the next run can overwrite is not evidence,
        // and this is the table somebody reads after an incident to see what was known when.
        assertThatThrownBy(() -> jdbc.update(
                        "update reconciliation_run set matched = 999 where id = ?",
                        found.runId()))
                .hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> jdbc.update(
                        "delete from reconciliation_run where id = ?", found.runId()))
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void adjustsNothing() {
        UUID merchant = UUID.randomUUID();
        UUID payment = captured(merchant, "acq_off", 100_00);
        theBankSays(settled("acq_off", 90_00));

        reconciliation.reconcile(day, "TRY");

        // Reconciliation reports. A job that could silently make the books agree with the
        // bank is a job that could silently make them wrong, and what to do about a
        // difference is a person's decision.
        assertThat(jdbc.queryForObject(
                        "select amount from settleable where payment_id = ?", Long.class, payment))
                .isEqualTo(100_00L);
    }

    // -- helpers ---------------------------------------------------------------------------

    private void theBankSays(AcquirerStatements.Settled... settled) {
        List<AcquirerStatements.Settled> rows = List.of(settled);
        org.mockito.Mockito.when(statements.forDay(day, "TRY"))
                .thenReturn(new AcquirerStatements.Statement(
                        day,
                        "TRY",
                        rows,
                        rows.size(),
                        rows.stream().mapToLong(AcquirerStatements.Settled::amount).sum()));
    }

    private static AcquirerStatements.Settled settled(String reference, long amount) {
        return new AcquirerStatements.Settled(reference, amount, "TRY");
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
                acquirerReference);
        return payment;
    }

    private List<Map<String, Object>> outstanding(LocalDate day) {
        return jdbc.queryForList(
                "select * from reconciliation_difference where settled_for = ? "
                        + "order by acquirer_reference",
                day);
    }

    private Map<String, Object> onlyOutstanding(LocalDate day) {
        List<Map<String, Object>> found = outstanding(day);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private Instant seenAt(LocalDate day, String column) {
        return jdbc.queryForObject(
                "select " + column + " from reconciliation_difference where settled_for = ?",
                Instant.class,
                day);
    }
}
