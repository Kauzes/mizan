package dev.kauzes.mizan.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kauzes.mizan.common.web.inbox.Inbox;
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A consumer that sees an event twice and acts once.
 *
 * <p>MIZ-48 delivers at least once by design, so redelivery is this platform working normally
 * rather than failing. A handler not built for it sends a customer a second receipt on an
 * ordinary restart, which is why the interesting tests here are the ones that deliver the same
 * event more than once.
 *
 * <p>The messages here are written by this test, so that a delivery can be repeated exactly
 * and a failure can be provoked. That means they are the right shape only for as long as
 * somebody keeps them so — which is what {@link PaymentEventContractTest} is for: it starts
 * the real payment service and lets it publish, so the day the shape changes, something
 * fails.
 */
@SpringBootTest
class PaymentNotificationsTest extends MizanIntegrationTest {

    private static final String TOPIC = "mizan.payment.events";

    @DynamicPropertySource
    static void useAGroupOfOurOwn(DynamicPropertyRegistry registry) {
        // A fresh group per run, so a test reads the topic from the beginning rather than
        // from wherever a previous run left this service's offsets.
        registry.add("spring.kafka.consumer.group-id", () -> "notification-test-" + UUID.randomUUID());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Inbox inbox;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @BeforeEach
    void startClean() {
        jdbc.update("delete from notification");
        jdbc.update("delete from handled_event");
    }

    @Test
    @Timeout(120)
    void aCapturedPaymentBecomesSomethingToTellTheMerchant() {
        UUID payment = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();

        publish(captured(UUID.randomUUID(), payment, merchant, "order-77", 125000));

        Map<String, Object> notification = eventually(payment);
        assertThat(notification.get("kind")).isEqualTo("PAYMENT_CAPTURED");
        assertThat(notification.get("merchant_id")).hasToString(merchant.toString());
        assertThat(notification.get("message"))
                .as("minor units are not what a person reads, and the currency decides where "
                        + "the point goes")
                .isEqualTo("You have been paid 1250.00 TRY for order-77.");
    }

    @Test
    @Timeout(120)
    void theSameEventDeliveredThreeTimesIsActedOnOnce() {
        UUID payment = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        // The same event, not three events about the same payment. This is what a relay that
        // died between publishing and marking the row produces, and what a consumer group
        // rebalancing mid-batch produces, and neither is a fault.
        publish(captured(eventId, payment, UUID.randomUUID(), "order-88", 50000));
        publish(captured(eventId, payment, UUID.randomUUID(), "order-88", 50000));
        publish(captured(eventId, payment, UUID.randomUUID(), "order-88", 50000));

        eventually(payment);
        sleep(Duration.ofSeconds(2));

        assertThat(notificationsFor(payment))
                .as("one customer, one receipt")
                .hasSize(1);
        assertThat(handledCount(eventId)).isEqualTo(1);
    }

    @Test
    @Timeout(120)
    void anEventItHasNothingToSayAboutIsStillRecordedAsSeen() {
        UUID payment = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        // An authorization is a promise that money is there, not money arriving. There is
        // nothing to tell a merchant, and the event is recorded anyway so that it is not
        // reconsidered on every redelivery for as long as the topic keeps it.
        publish(event(eventId, "payment.authorized", payment, UUID.randomUUID(), """
                {"paymentId":"%s","reference":"order-99","amount":1000,"currency":"TRY",
                 "acquirerReference":"auth_x","cardLastFour":"0000","at":"%s"}
                """.formatted(payment, Instant.now())));

        waitUntilHandled(eventId);
        assertThat(notificationsFor(payment)).isEmpty();
    }

    @Test
    @Timeout(120)
    void aDeclineKeepsTheReasonTheAcquirerGave() {
        UUID payment = UUID.randomUUID();

        publish(event(UUID.randomUUID(), "payment.declined", payment, UUID.randomUUID(), """
                {"paymentId":"%s","reference":"order-11","amount":67500,"currency":"TRY",
                 "acquirerReference":"auth_y","cardLastFour":"0002",
                 "reason":"insufficient_funds","at":"%s"}
                """.formatted(payment, Instant.now())));

        assertThat(eventually(payment).get("message"))
                .isEqualTo("A payment of 675.00 TRY for order-11 was declined: "
                        + "insufficient_funds.");
    }

    @Test
    void handlingAndTheRecordOfHavingHandledItCommitTogether() {
        UUID eventId = UUID.randomUUID();
        String message = captured(eventId, UUID.randomUUID(), UUID.randomUUID(), "order-22", 100);

        // A handler that fails must leave no trace, or the event is never retried and whatever
        // it was supposed to do never happens. This is the same rule as the outbox, from the
        // other end.
        assertThatThrownBy(() -> inbox.once("a-handler-that-fails", message, event -> {
                    throw new IllegalStateException("nope");
                }))
                .hasMessage("nope");

        assertThat(handledCount(eventId))
                .as("nothing recorded, so it will be delivered again and tried again")
                .isZero();
    }

    @Test
    void twoHandlersEachGetToSeeTheSameEvent() {
        UUID eventId = UUID.randomUUID();
        String message = captured(eventId, UUID.randomUUID(), UUID.randomUUID(), "order-33", 100);
        AtomicInteger ran = new AtomicInteger();

        inbox.once("one-handler", message, event -> ran.incrementAndGet());
        inbox.once("another-handler", message, event -> ran.incrementAndGet());
        inbox.once("one-handler", message, event -> ran.incrementAndGet());

        assertThat(ran.get())
                .as("already handled is a question about a handler, not about a service")
                .isEqualTo(2);
    }

    @Test
    @Timeout(180)
    void aConsumerThatWasNotListeningCatchesUp() {
        UUID payment = UUID.randomUUID();
        var container = listeners.getListenerContainer("payment-notifications");

        container.stop();
        try {
            publish(captured(UUID.randomUUID(), payment, UUID.randomUUID(), "order-44", 25000));
            sleep(Duration.ofSeconds(2));
            assertThat(notificationsFor(payment))
                    .as("nothing is listening, so nothing has happened yet")
                    .isEmpty();
        } finally {
            container.start();
        }

        // Nobody replays anything by hand. The offsets are the broker's memory of where this
        // group had got to, and it picks up from there.
        assertThat(eventually(payment).get("kind")).isEqualTo("PAYMENT_CAPTURED");
    }

    // -- producing --------------------------------------------------------------------------

    private static String captured(
            UUID eventId, UUID payment, UUID merchant, String reference, long amount) {

        return event(eventId, "payment.captured", payment, merchant, """
                {"paymentId":"%s","merchantId":"%s","reference":"%s","amount":%d,
                 "currency":"TRY","acquirerReference":"auth_z","ledgerEntryId":"%s","at":"%s"}
                """.formatted(payment, merchant, reference, amount, UUID.randomUUID(),
                        Instant.now()));
    }

    /** The envelope exactly as {@code KafkaEventPublisher} writes it. */
    private static String event(
            UUID eventId, String type, UUID aggregate, UUID merchant, String payload) {

        return """
                {"eventId":"%s","type":"%s","version":1,"aggregateType":"payment",
                 "aggregateId":"%s","merchantId":"%s","occurredAt":"%s",
                 "correlationId":"a-request-somewhere","payload":%s}
                """.formatted(eventId, type, aggregate, merchant, Instant.now(), payload);
    }

    private void publish(String message) {
        Properties settings = new Properties();
        settings.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                MizanContainers.kafka().getBootstrapServers());
        settings.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        settings.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(settings)) {
            String key = tools.jackson.databind.json.JsonMapper.builder()
                    .build()
                    .readTree(message)
                    .path("aggregateId")
                    .asString();
            producer.send(new ProducerRecord<>(TOPIC, key, message)).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (java.util.concurrent.ExecutionException notSent) {
            throw new IllegalStateException(notSent);
        }
    }

    // -- reading ------------------------------------------------------------------------

    private List<Map<String, Object>> notificationsFor(UUID payment) {
        return jdbc.queryForList("select * from notification where payment_id = ?", payment);
    }

    private long handledCount(UUID eventId) {
        Long counted = jdbc.queryForObject(
                "select count(*) from handled_event where event_id = ?", Long.class, eventId);
        return counted == null ? 0 : counted;
    }

    /** Waits for the one notification about this payment, because a consumer is not instant. */
    private Map<String, Object> eventually(UUID payment) {
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < giveUpAt) {
            List<Map<String, Object>> found = notificationsFor(payment);
            if (!found.isEmpty()) {
                return found.getFirst();
            }
            sleep(Duration.ofMillis(200));
        }
        throw new AssertionError("nothing was ever said about payment " + payment);
    }

    private void waitUntilHandled(UUID eventId) {
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < giveUpAt) {
            if (handledCount(eventId) > 0) {
                return;
            }
            sleep(Duration.ofMillis(200));
        }
        throw new AssertionError("event " + eventId + " was never handled");
    }

    private static void sleep(Duration howLong) {
        try {
            Thread.sleep(howLong.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
