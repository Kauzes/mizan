package dev.kauzes.mizan.common.web.outbox;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes what the outbox holds and publishes it.
 *
 * <p><b>At least once, and not exactly once.</b> The relay publishes and then marks the row,
 * and a process that dies in between will publish again. That window can be made small and
 * cannot be closed: marking first would lose events instead, which is worse, and there is no
 * way to make a database commit and a broker acknowledgement one atomic act. Saying so plainly
 * is what makes idempotent consumers obviously necessary rather than a nicety — that is
 * MIZ-49.
 *
 * <h2>More than one of these can run</h2>
 *
 * <p>Rows are claimed with {@code for update skip locked}, so two instances take different
 * work rather than blocking on each other or publishing the same row twice.
 *
 * <p>That alone would not preserve order. If one instance holds a payment's earlier event and
 * another picks up its later one, the later could be published first, and a consumer would
 * see a capture before the authorization it belongs to. So before publishing anything for an
 * aggregate, the relay asks whether anything older for that aggregate is still unpublished. If
 * there is, somebody else has it or it is waiting to be retried, and this aggregate is left
 * alone until the next pass. Nothing is lost by waiting; something is lost by not.
 *
 * <h2>What a failure blocks, and what it does not</h2>
 *
 * <p>An event that will not publish blocks its own aggregate's later events, necessarily: they
 * cannot go out in order until it does. It blocks nothing else. Every other payment keeps
 * flowing, because the check above is per aggregate and the retry delay takes the failing row
 * out of the running until its time comes.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * How many rows one pass claims.
     *
     * <p>Small on purpose. The claim holds row locks while events are published, which is
     * network work, and a big batch would hold them for a long time. Nothing is blocked by
     * that — other instances skip locked rows — but a batch that takes a minute is also a
     * batch whose whole transaction is lost if anything goes wrong at the end of it.
     */
    private final int batchSize;

    private final JdbcTemplate jdbc;
    private final EventPublisher publisher;
    private final TransactionTemplate transaction;
    private final Duration firstRetry;
    private final Duration longestRetry;

    public OutboxRelay(
            JdbcTemplate jdbc,
            EventPublisher publisher,
            PlatformTransactionManager transactions,
            int batchSize,
            Duration firstRetry,
            Duration longestRetry) {

        this.jdbc = jdbc;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactions);
        this.batchSize = batchSize;
        this.firstRetry = firstRetry;
        this.longestRetry = longestRetry;
    }

    /**
     * One pass: claim what is due, publish it in order, mark what went.
     *
     * @return how many events were published
     */
    public int drain() {
        Integer published = transaction.execute(status -> {
            List<PendingEvent> claimed = claim();
            if (claimed.isEmpty()) {
                return 0;
            }

            int sent = 0;
            for (Map.Entry<UUID, List<PendingEvent>> aggregate : byAggregate(claimed).entrySet()) {
                sent += publishInOrder(aggregate.getKey(), aggregate.getValue());
            }
            return sent;
        });

        return published == null ? 0 : published;
    }

    /**
     * Everything unpublished and due, oldest first, locked so nobody else takes it.
     *
     * <p>{@code skip locked} rather than waiting: a second instance should get on with other
     * work rather than queue behind this one.
     */
    private List<PendingEvent> claim() {
        return jdbc.query(
                "select id, type, version, aggregate_type, aggregate_id, merchant_id, "
                        + "occurred_at, correlation_id, payload::text as payload, sequence, "
                        + "attempts "
                        + "from outbox_event "
                        + "where published_at is null "
                        + "and (next_attempt_at is null or next_attempt_at <= now()) "
                        + "order by sequence "
                        + "limit ? "
                        + "for update skip locked",
                (row, index) -> new PendingEvent(
                        row.getObject("id", UUID.class),
                        row.getString("type"),
                        row.getInt("version"),
                        row.getString("aggregate_type"),
                        row.getObject("aggregate_id", UUID.class),
                        row.getObject("merchant_id", UUID.class),
                        row.getTimestamp("occurred_at").toInstant(),
                        row.getString("correlation_id"),
                        row.getString("payload"),
                        row.getLong("sequence"),
                        row.getInt("attempts")),
                batchSize);
    }

    /** Grouped, each aggregate's events still in the order they were written. */
    private static Map<UUID, List<PendingEvent>> byAggregate(List<PendingEvent> claimed) {
        Map<UUID, List<PendingEvent>> grouped = new LinkedHashMap<>();
        for (PendingEvent event : claimed) {
            grouped.computeIfAbsent(event.aggregateId(), id -> new java.util.ArrayList<>())
                    .add(event);
        }
        return grouped;
    }

    private int publishInOrder(UUID aggregateId, List<PendingEvent> events) {
        if (somethingOlderIsStillWaiting(aggregateId, events.getFirst().sequence())) {
            // Another instance has it, or it failed and is waiting to be retried. Either way
            // publishing this one now would put a consumer's view of this payment out of
            // order, and waiting costs only a pass.
            log.debug("leaving {} alone: something older is still unpublished", aggregateId);
            return 0;
        }

        int sent = 0;
        for (PendingEvent event : events) {
            try {
                publisher.publish(event);
            } catch (RuntimeException notSent) {
                // Stop at the first failure rather than carrying on down the list. The rest
                // of this aggregate's events must not overtake the one that did not go.
                defer(event, notSent);
                break;
            }
            markPublished(event);
            sent++;
        }
        return sent;
    }

    private boolean somethingOlderIsStillWaiting(UUID aggregateId, long oldestClaimed) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from outbox_event where aggregate_id = ? "
                        + "and published_at is null and sequence < ?)",
                Boolean.class,
                aggregateId,
                oldestClaimed));
    }

    private void markPublished(PendingEvent event) {
        jdbc.update(
                "update outbox_event set published_at = ? where id = ?",
                Timestamp.from(Instant.now()),
                event.eventId());
        log.debug("published {} {} for {}", event.type(), event.eventId(), event.aggregateId());
    }

    /**
     * Puts a failed event out of the running for a while, and says why in the row.
     *
     * <p>Longer each time, and never longer than the cap, so a broker that is down for an hour
     * is retried through that hour rather than every second of it. The jitter is so that a
     * hundred events deferred by one outage do not all come back at the same instant.
     */
    private void defer(PendingEvent event, RuntimeException cause) {
        Duration wait = backoffFor(event.attempts() + 1);

        jdbc.update(
                "update outbox_event set attempts = attempts + 1, next_attempt_at = ?, "
                        + "last_error = ? where id = ?",
                Timestamp.from(Instant.now().plus(wait)),
                describe(cause),
                event.eventId());

        log.warn(
                "could not publish {} {}, attempt {}; trying again in {}",
                event.type(),
                event.eventId(),
                event.attempts() + 1,
                wait,
                cause);
    }

    private Duration backoffFor(int attempt) {
        // Doubling, capped, then jittered by up to a quarter either way.
        long base = Math.min(
                longestRetry.toMillis(),
                firstRetry.toMillis() * (1L << Math.min(attempt - 1, 20)));
        long jitter = base / 4;
        return Duration.ofMillis(
                base + (jitter == 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitter, jitter)));
    }

    /** Enough to explain the row later, and not a stack trace in a text column. */
    private static String describe(RuntimeException cause) {
        String message = cause.getMessage() == null ? "" : ": " + cause.getMessage();
        String described = cause.getClass().getSimpleName() + message;
        return described.length() > 1000 ? described.substring(0, 1000) : described;
    }
}
