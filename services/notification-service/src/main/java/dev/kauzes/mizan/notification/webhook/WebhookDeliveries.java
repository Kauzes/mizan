package dev.kauzes.mizan.notification.webhook;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The deliveries waiting to be made, and the record of every attempt at one.
 *
 * <p>Plain SQL for the same reason the outbox and the inbox are: this is a work queue read with
 * {@code for update skip locked}, which is a thing to say to a database rather than to an
 * object graph.
 */
@Component
public class WebhookDeliveries {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveries.class);

    /** One delivery, as a worker needs it. */
    public record Due(
            UUID id,
            UUID merchantId,
            UUID endpointId,
            String url,
            String encryptedSecret,
            String eventType,
            String body,
            int attempts) {
    }

    private final JdbcTemplate jdbc;

    public WebhookDeliveries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that a merchant is to be told, in the caller's transaction.
     *
     * <p>Called from inside the inbox transaction that decided the notification, so the
     * decision and the deliveries it implies commit together. The same reasoning as the outbox:
     * a notification with no deliveries is a merchant who is never told, and a delivery with no
     * notification is a merchant told about something the platform does not believe.
     */
    public void queue(
            UUID id,
            UUID merchantId,
            UUID endpointId,
            UUID notificationId,
            UUID paymentId,
            String eventType,
            String body) {

        jdbc.update(
                "insert into webhook_delivery (id, merchant_id, endpoint_id, notification_id, "
                        + "payment_id, event_type, body, status, next_attempt_at, created_at, "
                        + "updated_at) values (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?) "
                        // One delivery per notification per endpoint. A redelivered event that
                        // races the original must not tell the merchant twice.
                        + "on conflict (notification_id, endpoint_id) do nothing",
                id,
                merchantId,
                endpointId,
                notificationId,
                paymentId,
                eventType,
                body,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
    }

    /**
     * Claims what is due, oldest first, without waiting for anybody.
     *
     * <p>{@code skip locked} is the whole answer to "a slow endpoint must not delay anybody
     * else". A worker holding a delivery to an endpoint that never answers holds one row; every
     * other worker steps over it and gets on with somebody else's. Without it they would queue,
     * and one broken merchant would stop the platform telling anyone anything.
     *
     * <p>The rows are claimed and the transaction ends immediately. The HTTP call happens
     * outside it, because holding a database transaction open across a call to somebody else's
     * server for as long as they feel like taking is how a connection pool is exhausted by one
     * merchant.
     */
    public List<Due> claim(int howMany) {
        List<UUID> ids = jdbc.queryForList(
                "select id from webhook_delivery where status = 'PENDING' "
                        + "and (next_attempt_at is null or next_attempt_at <= now()) "
                        + "order by next_attempt_at limit ? for update skip locked",
                UUID.class,
                howMany);

        if (ids.isEmpty()) {
            return List.of();
        }

        // Taken out of the running immediately, so the next pass — in this process or another
        // — does not pick up something already in flight. A crash between here and the attempt
        // means the delivery waits for the backoff and is then retried, which is the correct
        // outcome and the reason attempts are counted at claim time rather than at send time.
        jdbc.update(
                "update webhook_delivery set attempts = attempts + 1, next_attempt_at = ?, "
                        + "updated_at = now() where id = any (?)",
                Timestamp.from(Instant.now().plus(Duration.ofMinutes(5))),
                ids.toArray(UUID[]::new));

        return jdbc.query(
                "select d.id, d.merchant_id, d.endpoint_id, e.url, e.secret, d.event_type, "
                        + "d.body, d.attempts "
                        + "from webhook_delivery d join webhook_endpoint e on e.id = d.endpoint_id "
                        + "where d.id = any (?) and e.enabled = true",
                (row, index) -> new Due(
                        row.getObject("id", UUID.class),
                        row.getObject("merchant_id", UUID.class),
                        row.getObject("endpoint_id", UUID.class),
                        row.getString("url"),
                        row.getString("secret"),
                        row.getString("event_type"),
                        row.getString("body"),
                        row.getInt("attempts")),
                (Object) ids.toArray(UUID[]::new));
    }

    /** It arrived, and the merchant said so. */
    public void delivered(UUID id, int attempt, int statusCode, long tookMillis) {
        recordAttempt(id, attempt, statusCode, tookMillis, null);
        jdbc.update(
                "update webhook_delivery set status = 'DELIVERED', delivered_at = ?, "
                        + "last_status_code = ?, last_error = null, next_attempt_at = null, "
                        + "updated_at = now() where id = ?",
                Timestamp.from(Instant.now()),
                statusCode,
                id);
    }

    /**
     * It did not arrive, and will be tried again, or will not.
     *
     * @return whether this was the last attempt
     */
    public boolean failed(
            UUID id, int attempt, Integer statusCode, long tookMillis, String error, int limit) {

        recordAttempt(id, attempt, statusCode, tookMillis, error);

        boolean giveUp = attempt >= limit;
        if (giveUp) {
            jdbc.update(
                    "update webhook_delivery set status = 'FAILED', last_status_code = ?, "
                            + "last_error = ?, next_attempt_at = null, updated_at = now() "
                            + "where id = ?",
                    statusCode,
                    trim(error),
                    id);
            log.error(
                    "GIVING UP on webhook delivery {} after {} attempts: {}",
                    id,
                    attempt,
                    error);
        } else {
            jdbc.update(
                    "update webhook_delivery set last_status_code = ?, last_error = ?, "
                            + "next_attempt_at = ?, updated_at = now() where id = ?",
                    statusCode,
                    trim(error),
                    Timestamp.from(Instant.now().plus(waitAfter(attempt))),
                    id);
        }
        return giveUp;
    }

    /**
     * Puts a delivery back in the queue, whatever state it reached.
     *
     * <p>From attempt zero, because the reason somebody is asking for this is that something
     * has changed and the old count describes a world that no longer exists.
     */
    public void redeliver(UUID id) {
        jdbc.update(
                "update webhook_delivery set status = 'PENDING', attempts = 0, "
                        + "next_attempt_at = ?, delivered_at = null, updated_at = now() "
                        + "where id = ?",
                Timestamp.from(Instant.now()),
                id);
    }

    private void recordAttempt(
            UUID delivery, int attempt, Integer statusCode, long tookMillis, String error) {

        jdbc.update(
                "insert into webhook_delivery_attempt (id, delivery_id, attempt, at, "
                        + "status_code, duration_ms, error) values (?, ?, ?, ?, ?, ?, ?) "
                        + "on conflict (delivery_id, attempt) do nothing",
                UUID.randomUUID(),
                delivery,
                attempt,
                Timestamp.from(Instant.now()),
                statusCode,
                tookMillis,
                trim(error));
    }

    /**
     * How long before the next attempt: longer each time, and never exactly as long as anybody
     * else's.
     *
     * <p>A merchant's endpoint that has just failed is likely to still be failing in a second,
     * and the jitter is so that a hundred deliveries queued during one outage do not all
     * return at the same instant and finish the job the outage started.
     */
    static Duration waitAfter(int attempt) {
        long seconds = Math.min(3600, 5L << Math.min(attempt, 10));
        long jitter = Math.max(1, seconds / 4);
        return Duration.ofSeconds(seconds + ThreadLocalRandom.current().nextLong(-jitter, jitter));
    }

    private static String trim(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 1000 ? error.substring(0, 1000) : error;
    }

    // -- what a merchant reads ---------------------------------------------------------

    public List<Map<String, Object>> forEndpoint(UUID merchantId, UUID endpointId, int limit) {
        return jdbc.queryForList(
                "select id, notification_id, payment_id, event_type, status, attempts, "
                        + "last_status_code, last_error, delivered_at, created_at, updated_at "
                        + "from webhook_delivery where merchant_id = ? and endpoint_id = ? "
                        + "order by created_at desc limit ?",
                merchantId,
                endpointId,
                limit);
    }

    /**
     * What was sent to this merchant, optionally about one payment.
     *
     * <p>The endpoint id comes back with each row on purpose: it is what lets a caller ask
     * for the attempts behind a delivery without this having to grow a second route that
     * answers the same question.
     */
    public List<Map<String, Object>> forMerchant(UUID merchantId, UUID paymentId, int limit) {
        String columns = "select id, endpoint_id, notification_id, payment_id, event_type, "
                + "status, attempts, last_status_code, last_error, delivered_at, created_at, "
                + "updated_at from webhook_delivery where merchant_id = ?";

        if (paymentId == null) {
            return jdbc.queryForList(columns + " order by created_at desc limit ?",
                    merchantId, limit);
        }
        return jdbc.queryForList(
                columns + " and payment_id = ? order by created_at desc limit ?",
                merchantId,
                paymentId,
                limit);
    }

    public List<Map<String, Object>> attemptsOf(UUID merchantId, UUID deliveryId) {
        return jdbc.queryForList(
                "select a.attempt, a.at, a.status_code, a.duration_ms, a.error "
                        + "from webhook_delivery_attempt a "
                        + "join webhook_delivery d on d.id = a.delivery_id "
                        + "where d.merchant_id = ? and a.delivery_id = ? order by a.attempt",
                merchantId,
                deliveryId);
    }

    public java.util.Optional<Map<String, Object>> find(UUID merchantId, UUID deliveryId) {
        return jdbc
                .queryForList(
                        "select * from webhook_delivery where merchant_id = ? and id = ?",
                        merchantId,
                        deliveryId)
                .stream()
                .findFirst();
    }
}
