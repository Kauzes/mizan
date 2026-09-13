package dev.kauzes.mizan.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * What this service measures about telling merchants things.
 *
 * <p>Two questions, and they are not the same one. <b>Is anything stuck</b>: an event that
 * could not be handled has been set aside, and until somebody looks at it, something a
 * merchant should have been told never happened. <b>Is anything late</b>: a webhook that
 * arrives eventually and a webhook that arrives in an hour are different products, and the
 * difference does not show up in a count of deliveries — only in how old the oldest one
 * waiting is.
 *
 * <p>The lag is measured as the age of the oldest delivery still waiting, which is the figure
 * that tells somebody how bad it is right now. An average delivery time would be reassuring
 * during exactly the incident worth noticing, because the deliveries that are stuck are the
 * ones not in it yet.
 *
 * <p>No merchant id and no endpoint on any of these. The point of a metric is a small closed
 * set of values, and "which merchant" is a dimension with no upper bound — it is also one
 * merchant's business, sitting in a system with no access control on it.
 */
@Component
public class NotificationMetrics {

    private final JdbcTemplate jdbc;

    private final AtomicLong deadLetters = new AtomicLong();
    private final AtomicLong waitingToBeDelivered = new AtomicLong();
    private final AtomicLong oldestWaitingSeconds = new AtomicLong();
    private final AtomicLong givenUpOn = new AtomicLong();

    private final Counter delivered;
    private final Counter failed;

    public NotificationMetrics(MeterRegistry meters, JdbcTemplate jdbc) {
        this.jdbc = jdbc;

        Gauge.builder("mizan.deadletters.outstanding", deadLetters, AtomicLong::get)
                .description("Events set aside because no amount of retrying would handle them")
                .register(meters);

        Gauge.builder("mizan.webhooks.waiting", waitingToBeDelivered, AtomicLong::get)
                .description("Webhook deliveries that have not reached the merchant yet")
                .register(meters);

        Gauge.builder("mizan.webhooks.lag.seconds", oldestWaitingSeconds, AtomicLong::get)
                .description("How long the oldest undelivered webhook has been waiting")
                .register(meters);

        Gauge.builder("mizan.webhooks.given.up.on", givenUpOn, AtomicLong::get)
                .description("Deliveries this platform has stopped attempting")
                .register(meters);

        this.delivered = Counter.builder("mizan.webhooks.attempts")
                .description("Webhook delivery attempts, by what became of them")
                .tag("outcome", "delivered")
                .register(meters);

        this.failed = Counter.builder("mizan.webhooks.attempts")
                .description("Webhook delivery attempts, by what became of them")
                .tag("outcome", "failed")
                .register(meters);
    }

    /** The merchant's endpoint accepted it. */
    public void delivered() {
        delivered.increment();
    }

    /** It did not, and this attempt is over. Whether another follows is the dispatcher's. */
    public void failed() {
        failed.increment();
    }

    /**
     * Counts what is waiting, on a timer.
     *
     * <p>On a timer rather than when the scrape asks, because a gauge that queries the database
     * from the scrape's thread makes monitoring slow exactly when a slow database is the thing
     * being monitored.
     */
    @Scheduled(
            fixedDelayString = "${mizan.metrics.count-every:15s}",
            initialDelayString = "${mizan.metrics.count-first-after:10s}")
    public void countWhatIsWaiting() {
        deadLetters.set(countOf(
                "select count(*) from dead_letter where redelivered_at is null"));
        waitingToBeDelivered.set(countOf(
                "select count(*) from webhook_delivery where status = 'PENDING'"));
        givenUpOn.set(countOf(
                "select count(*) from webhook_delivery where status = 'FAILED'"));

        Long oldest = jdbc.queryForObject(
                """
                select coalesce(
                    extract(epoch from now() - min(created_at))::bigint, 0)
                from webhook_delivery where status = 'PENDING'
                """,
                Long.class);
        oldestWaitingSeconds.set(oldest == null ? 0L : oldest);
    }

    private long countOf(String sql) {
        Long counted = jdbc.queryForObject(sql, Long.class);
        return counted == null ? 0L : counted;
    }
}
