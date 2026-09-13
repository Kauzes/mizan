package dev.kauzes.mizan.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.test.MizanIntegrationTest;
import dev.kauzes.mizan.notification.webhook.WebhookDeliveries;
import dev.kauzes.mizan.notification.webhook.WebhookEndpointService;
import dev.kauzes.mizan.notification.webhook.WebhookRequests.RegisterEndpointRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Is anything stuck, and is anything late.
 *
 * <p>Two different questions, and a count of deliveries answers neither. An event set aside
 * means something a merchant should have been told never happened; a delivery waiting an hour
 * means they will be told, eventually, which is a different product from being told now.
 *
 * <p>Each of these makes the thing happen and watches the number move, because a metric that
 * is always zero is indistinguishable from a platform where nothing is wrong.
 */
@SpringBootTest(properties = {
    // Driven by hand, so what the numbers say is a fact rather than a race with a scheduler.
    "mizan.metrics.count-first-after=3650d",
    "mizan.webhooks.deliver-every=3650d",
    // Nothing here sends anything; the endpoints exist so that deliveries can belong to one.
    // The destination check does a DNS lookup, which would make this class fail on a machine
    // with no network for a reason that has nothing to do with counting.
    "mizan.webhooks.allow-any-destination=true"
})
class NotificationMetricsTest extends MizanIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private NotificationMetrics metrics;

    @Autowired
    private WebhookDeliveries deliveries;

    @Autowired
    private WebhookEndpointService endpoints;

    @BeforeEach
    void startClean() {
        jdbc.update("delete from webhook_delivery_attempt");
        jdbc.update("delete from webhook_delivery");
        jdbc.update("delete from webhook_subscription");
        jdbc.update("delete from webhook_endpoint");
        jdbc.update("delete from dead_letter");
    }

    @Test
    void countsAnEventNobodyCouldHandle() {
        metrics.countWhatIsWaiting();
        assertThat(gauge("mizan.deadletters.outstanding")).isZero();

        setAside();
        metrics.countWhatIsWaiting();

        // The number that means something a merchant should have been told never happened.
        assertThat(gauge("mizan.deadletters.outstanding")).isEqualTo(1);
    }

    @Test
    void stopsCountingOneThatHasBeenSentBackRound() {
        setAside();
        jdbc.update("update dead_letter set redelivered_at = now()");

        metrics.countWhatIsWaiting();

        // Redelivered is handled. A queue that kept counting what an operator had already
        // dealt with would be a queue nobody believes.
        assertThat(gauge("mizan.deadletters.outstanding")).isZero();
    }

    @Test
    void saysHowLongTheOldestWebhookHasBeenWaiting() {
        waiting(Instant.now().minus(90, ChronoUnit.MINUTES));
        waiting(Instant.now().minus(1, ChronoUnit.MINUTES));

        metrics.countWhatIsWaiting();

        assertThat(gauge("mizan.webhooks.waiting")).isEqualTo(2);
        // The oldest, not the average. An average would be reassuring during exactly the
        // incident worth noticing, because the deliveries that are stuck are the ones not in
        // it yet.
        assertThat(gauge("mizan.webhooks.lag.seconds")).isBetween(89.0 * 60, 91.0 * 60);
    }

    @Test
    void saysNothingIsLateWhenNothingIsWaiting() {
        metrics.countWhatIsWaiting();

        // Zero rather than absent, and zero rather than the age of the last thing delivered.
        // A gauge that disappears when all is well is a gauge whose alert never fires either.
        assertThat(gauge("mizan.webhooks.lag.seconds")).isZero();
    }

    @Test
    void countsWhatThisPlatformHasStoppedAttempting() {
        givenUpOn();

        metrics.countWhatIsWaiting();

        // Not the same as waiting. These will never arrive without somebody doing something,
        // and they are invisible in a count of what is pending.
        assertThat(gauge("mizan.webhooks.given.up.on")).isEqualTo(1);
        assertThat(gauge("mizan.webhooks.waiting")).isZero();
    }

    @Test
    void countsAttemptsByWhatBecameOfThem() {
        double delivered = counter("mizan.webhooks.attempts", "delivered");
        double failed = counter("mizan.webhooks.attempts", "failed");

        metrics.delivered();
        metrics.failed();
        metrics.failed();

        assertThat(counter("mizan.webhooks.attempts", "delivered")).isEqualTo(delivered + 1);
        assertThat(counter("mizan.webhooks.attempts", "failed")).isEqualTo(failed + 2);
    }

    // -- helpers ---------------------------------------------------------------------------

    private void setAside() {
        jdbc.update(
                """
                insert into dead_letter (id, event_id, type, handler, topic, partition,
                    "offset", message_key, reason, payload, attempts, first_failed_at,
                    last_failed_at)
                values (?, ?, 'payment.captured', 'notifications', 'mizan.payment.events', 0, 1,
                    ?, 'nothing could be done', '{}', 5, now(), now())
                """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString());
    }

    private void waiting(Instant since) {
        queued("PENDING", since);
    }

    private void givenUpOn() {
        queued("FAILED", Instant.now());
    }

    /**
     * A delivery in a given state, aged.
     *
     * <p>Queued through the service rather than inserted, because a delivery belongs to an
     * endpoint and the database says so. Then backdated, because what is being measured is how
     * long something has been waiting and a test that waited ninety minutes would not be run.
     */
    private void queued(String status, Instant since) {
        UUID merchant = UUID.randomUUID();
        UUID endpoint = anEndpointOf(merchant);
        UUID delivery = UUID.randomUUID();

        deliveries.queue(
                delivery,
                merchant,
                endpoint,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "payment.captured",
                "{\"type\":\"payment.captured\"}");

        jdbc.update(
                "update webhook_delivery set status = ?, created_at = ?, updated_at = ? "
                        + "where id = ?",
                status,
                Timestamp.from(since),
                Timestamp.from(since),
                delivery);
    }

    private UUID anEndpointOf(UUID merchant) {
        return endpoints
                .register(
                        merchant,
                        new RegisterEndpointRequest(
                                "https://merchant.invalid/hook",
                                "for counting",
                                Set.of("payment.captured")))
                .endpoint()
                .id();
    }

    private double gauge(String name) {
        return meters.find(name).gauges().stream().mapToDouble(Gauge::value).sum();
    }

    private double counter(String name, String outcome) {
        return meters.find(name).tag("outcome", outcome).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
}
