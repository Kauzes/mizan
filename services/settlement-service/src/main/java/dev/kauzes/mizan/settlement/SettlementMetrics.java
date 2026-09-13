package dev.kauzes.mizan.settlement;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * What this service measures: what is waiting for a person, and what is waiting to be paid.
 *
 * <p>The reconciliation queue is already said out loud in the logs by {@link SaysSoOutLoud},
 * and that is a different job from this one. A log line reaches whoever is reading logs; a
 * number reaches a dashboard and an alert rule, and the difference matters at three in the
 * morning on a weekend. MIZ-80 is what turns this into a page.
 *
 * <p>Deliberately no amounts. What this platform owes merchants is a real number a dashboard
 * might like, and it is also every merchant's business added together, sitting in a system
 * with no access control on it. A count of batches waiting says the same operational thing
 * without publishing anybody's takings.
 */
@Component
public class SettlementMetrics {

    private final Rulings rulings;

    private final AtomicLong differencesWaiting = new AtomicLong();
    private final AtomicLong oldestDifferenceSeconds = new AtomicLong();
    private final AtomicLong capturesNotYetSettled = new AtomicLong();
    private final AtomicLong batchesNotYetPaid = new AtomicLong();

    private final JdbcTemplate jdbc;

    public SettlementMetrics(MeterRegistry meters, Rulings rulings, JdbcTemplate jdbc) {
        this.rulings = rulings;
        this.jdbc = jdbc;

        Gauge.builder("mizan.reconciliation.differences.waiting", differencesWaiting,
                        AtomicLong::get)
                .description("Differences between this platform and the bank that nobody has "
                        + "ruled on")
                .register(meters);

        Gauge.builder("mizan.reconciliation.oldest.seconds", oldestDifferenceSeconds,
                        AtomicLong::get)
                .description("How long the oldest unruled difference has been waiting")
                .register(meters);

        Gauge.builder("mizan.settlement.captures.waiting", capturesNotYetSettled, AtomicLong::get)
                .description("Captures that have not been claimed by a batch yet")
                .register(meters);

        Gauge.builder("mizan.settlement.batches.unpaid", batchesNotYetPaid, AtomicLong::get)
                .description("Closed batches the merchant has not been paid for")
                .register(meters);
    }

    @Scheduled(
            fixedDelayString = "${mizan.metrics.count-every:15s}",
            initialDelayString = "${mizan.metrics.count-first-after:10s}")
    public void countWhatIsWaiting() {
        Rulings.Outstanding waiting = rulings.outstanding();
        differencesWaiting.set(waiting.count());

        // Zero rather than absent when the queue is empty. A gauge that disappears when all is
        // well is a gauge whose alert stops evaluating at the same moment.
        oldestDifferenceSeconds.set(
                waiting.oldest() == null
                        ? 0L
                        : Duration.between(waiting.oldest(), Instant.now()).toSeconds());

        capturesNotYetSettled.set(countOf(
                "select count(*) from settleable where batch_id is null"));
        batchesNotYetPaid.set(countOf(
                "select count(*) from settlement_batch where paid_at is null"));
    }

    private long countOf(String sql) {
        Long counted = jdbc.queryForObject(sql, Long.class);
        return counted == null ? 0L : counted;
    }
}
