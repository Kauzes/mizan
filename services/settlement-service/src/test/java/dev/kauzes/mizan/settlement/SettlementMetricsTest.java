package dev.kauzes.mizan.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.test.MizanIntegrationTest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The queue, as a number an alert rule can read.
 *
 * <p>{@link SaysSoOutLoud} already says this in the logs, and that is a different job: a log
 * line reaches whoever is reading logs, and a number reaches a dashboard and a rule that can
 * wake somebody at three in the morning. What is checked here is that the number moves when a
 * difference appears and moves back when a person rules on it — a gauge that only ever counted
 * up would make the queue look permanently broken, and one that never moved would make a real
 * backlog invisible.
 */
@SpringBootTest(properties = {
    "mizan.settlement.close-every=3650d",
    "mizan.reconciliation.say-first-after=3650d",
    // Counted by hand here. A gauge refreshed by a scheduler halfway through an assertion is
    // a test about the scheduler.
    "mizan.metrics.count-first-after=3650d"
})
class SettlementMetricsTest extends MizanIntegrationTest {

    /** A day nobody else is using, for the reason {@link ReconciliationTest} gives. */
    private final LocalDate day = LocalDate.of(2220, 1, 1)
            .plusDays(ThreadLocalRandom.current().nextInt(50_000));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Reconciliation reconciliation;

    @Autowired
    private Rulings rulings;

    @Autowired
    private SettlementMetrics metrics;

    @MockitoBean
    private AcquirerStatements statements;

    @Test
    void countsADifferenceUntilSomebodyRulesOnIt() {
        metrics.countWhatIsWaiting();
        double before = gauge("mizan.reconciliation.differences.waiting");

        UUID difference = aDifferenceNobodyHasSeen();
        metrics.countWhatIsWaiting();

        assertThat(gauge("mizan.reconciliation.differences.waiting")).isEqualTo(before + 1);

        rulings.rule(difference, "ACKNOWLEDGED", "ada@mizan.local", "the bank was right", null);
        metrics.countWhatIsWaiting();

        // Back down, because a person dealt with it. Nothing else takes it off the queue, and
        // nothing else may take it off this number either.
        assertThat(gauge("mizan.reconciliation.differences.waiting")).isEqualTo(before);
    }

    @Test
    void saysHowLongTheOldestDifferenceHasBeenWaiting() {
        UUID difference = aDifferenceNobodyHasSeen();
        jdbc.update(
                "update reconciliation_difference set first_seen_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(3, ChronoUnit.HOURS)),
                difference);

        metrics.countWhatIsWaiting();

        // How bad it is, which a count alone does not say: one difference nobody has looked at
        // since Friday is a worse state than nine that turned up this morning.
        assertThat(gauge("mizan.reconciliation.oldest.seconds"))
                .isGreaterThanOrEqualTo(3 * 60 * 60);
    }

    @Test
    void countsCapturesThatNoBatchHasClaimed() {
        metrics.countWhatIsWaiting();
        double before = gauge("mizan.settlement.captures.waiting");

        captured("acq_unclaimed_" + day, 100_00);
        metrics.countWhatIsWaiting();

        // Not an amount. What this platform owes merchants is a real number a dashboard might
        // like, and it is also every merchant's takings added together in a system with no
        // access control on it.
        assertThat(gauge("mizan.settlement.captures.waiting")).isEqualTo(before + 1);
    }

    private double gauge(String name) {
        return meters.find(name).gauges().stream().mapToDouble(Gauge::value).sum();
    }

    private UUID aDifferenceNobodyHasSeen() {
        String reference = "acq_out_by_one_" + day;
        captured(reference, 100_00);

        List<AcquirerStatements.Settled> rows =
                List.of(new AcquirerStatements.Settled(reference, 90_00, "TRY"));
        Mockito.when(statements.forDay(day, "TRY"))
                .thenReturn(new AcquirerStatements.Statement(day, "TRY", rows, 1, 90_00));

        reconciliation.reconcile(day, "TRY");

        return jdbc.queryForObject(
                "select id from reconciliation_difference where acquirer_reference = ?",
                UUID.class,
                reference);
    }

    private void captured(String acquirerReference, long amount) {
        jdbc.update(
                """
                insert into settleable (payment_id, merchant_id, amount, currency,
                    captured_at, settled_for, acquirer_reference)
                values (?, ?, ?, 'TRY', ?, ?, ?)
                """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                amount,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                day,
                acquirerReference);
    }
}
