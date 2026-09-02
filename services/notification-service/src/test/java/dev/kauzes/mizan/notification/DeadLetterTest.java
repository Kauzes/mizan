package dev.kauzes.mizan.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.common.web.inbox.DeadLetters;
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What happens to an event nobody can handle.
 *
 * <p>Two failure modes are wrong and are what systems arrive at by accident. Retrying forever
 * blocks the partition and takes every well formed event behind the bad one down with it.
 * Dropping loses something that mattered. This checks the arrangement that is neither: a
 * bounded number of tries, then set aside somewhere a person can find it, with everything
 * behind it still flowing.
 */
@SpringBootTest(properties = {
    // Short, because these tests wait through them. The shape is what matters, not the numbers.
    "mizan.events.retries=2",
    "mizan.events.first-retry=200ms",
    "mizan.events.longest-retry=1s"
})
class DeadLetterTest extends MizanIntegrationTest {

    private static final String TOPIC = "mizan.payment.events";

    @DynamicPropertySource
    static void useGroupsOfOurOwn(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.kafka.consumer.group-id", () -> "dead-letter-test-" + UUID.randomUUID());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeadLetters deadLetters;

    @Autowired
    private DeadLetterEndpoint endpoint;

    @BeforeEach
    void startClean() {
        jdbc.update("delete from notification");
        jdbc.update("delete from handled_event");
        jdbc.update("delete from dead_letter");
    }

    @Test
    @Timeout(180)
    void anEventThatCannotBeReadIsSetAsideWithWhyAndWhatItWas() {
        String nonsense = "{\"this\":\"is not an event\"}";

        publish(UUID.randomUUID().toString(), nonsense);

        Map<String, Object> letter = eventuallyOneDeadLetter();
        assertThat(letter.get("reason"))
                .asString()
                .as("the useful sentence, not a stack trace nobody reads")
                .contains("not an event this service can read");
        assertThat(letter.get("payload"))
                .as("byte for byte, so a redelivery sends the event rather than our idea of it")
                .isEqualTo(nonsense);
        assertThat(letter.get("topic")).isEqualTo(TOPIC);
        assertThat(letter.get("attempts")).isEqualTo(1);
    }

    @Test
    @Timeout(180)
    void andEverythingBehindItStillArrives() {
        UUID before = UUID.randomUUID();
        UUID after = UUID.randomUUID();

        // All three on the same key, so they are in one partition and strictly in this order.
        // The poison one in the middle is the whole point: without a dead letter it would sit
        // at the head of that partition forever and the third would never be seen.
        String key = "one-partition-please";
        publish(key, captured(UUID.randomUUID(), before, UUID.randomUUID(), "order-before"));
        publish(key, "{\"not\":\"an event\"}");
        publish(key, captured(UUID.randomUUID(), after, UUID.randomUUID(), "order-after"));

        assertThat(eventuallyNotified(after))
                .as("the event behind the poison one arrived, which is what a dead letter is for")
                .isNotNull();
        assertThat(notificationsFor(before)).hasSize(1);
        assertThat(deadLetters.outstanding()).hasSize(1);
    }

    @Test
    @Timeout(180)
    void anOperatorCanSeeWhatIsOutstandingWithoutAKafkaConsole() {
        publish(UUID.randomUUID().toString(), "{\"still\":\"not an event\"}");
        eventuallyOneDeadLetter();

        Map<String, Object> report = endpoint.outstanding();
        assertThat(report.get("outstanding")).isEqualTo(1);
        assertThat(report.get("byHandler").toString()).contains("payment-notifications");
        assertThat(report.get("letters").toString()).contains("not an event this service can read");
    }

    @Test
    @Timeout(180)
    void redeliveringSendsItBackThroughTheOrdinaryPath() {
        UUID payment = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String good = captured(eventId, payment, UUID.randomUUID(), "order-rescued");

        // Standing in for "the bug is fixed": a dead letter whose payload this service can now
        // handle. Recorded directly, because provoking a transient failure and then repairing
        // it inside one test would be testing the contrivance rather than the redelivery.
        deadLetters.record(new DeadLetters.DeadLetter(
                null,
                eventId,
                "payment.captured",
                PaymentNotifications.HANDLER,
                TOPIC,
                0,
                0L,
                payment.toString(),
                "IllegalStateException: something that has since been fixed",
                "a-request-somewhere",
                good,
                0,
                null,
                null));

        UUID id = deadLetters.outstanding().getFirst().id();
        Map<String, Object> answer = endpoint.redeliver(id.toString());
        assertThat(answer).containsEntry("redelivered", eventId).containsEntry("to", TOPIC);

        // Back onto the same topic, through the same listener, through the same inbox. Nothing
        // about this delivery is special, which is the point: a redelivery down a path nothing
        // else uses would be a path tested only on the one event known to be difficult.
        assertThat(eventuallyNotified(payment)).isNotNull();
        assertThat(handledCount(eventId))
                .as("and the inbox recorded it exactly as it records any other")
                .isEqualTo(1);

        assertThat(jdbc.queryForObject(
                        "select redelivered_at is not null from dead_letter where id = ?",
                        Boolean.class,
                        id))
                .as("marked as dealt with, and kept: what went wrong is the useful part")
                .isTrue();
    }

    @Test
    @Timeout(180)
    void aFailureThatMightHavePassedIsRetriedFirstAndThenSetAside() {
        UUID payment = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        // A real event this handler chokes on: the currency is not one, so turning the amount
        // into something a person reads throws. Unlike an unreadable message, nothing declares
        // this hopeless, so it is retried the configured number of times before being set
        // aside — which is the behaviour a transient failure needs and this one does not get
        // to skip.
        publish(payment.toString(), capturedIn(eventId, payment, "NOTACURRENCY"));

        Map<String, Object> letter = eventuallyOneDeadLetter();
        assertThat(letter.get("event_id")).isEqualTo(eventId);
        assertThat(letter.get("type")).isEqualTo("payment.captured");
        assertThat(letter.get("correlation_id"))
                .as("the request that caused it, several services ago")
                .isEqualTo("a-request-somewhere");
        assertThat(notificationsFor(payment)).isEmpty();
    }

    @Test
    @Timeout(180)
    void oneThatFailsAgainIsOneRowWithACount() {
        UUID payment = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publish(payment.toString(), capturedIn(eventId, payment, "NOTACURRENCY"));
        Map<String, Object> first = eventuallyOneDeadLetter();
        UUID id = (UUID) first.get("id");

        endpoint.redeliver(id.toString());

        // Redelivered before the bug was actually fixed, which is the ordinary mistake. It
        // comes back, and it is the same row with a higher count rather than a growing pile
        // that hides how many distinct things are wrong.
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < giveUpAt) {
            Map<String, Object> row = jdbc.queryForMap(
                    "select attempts, redelivered_at from dead_letter where id = ?", id);
            if (Integer.valueOf(2).equals(row.get("attempts"))) {
                assertThat(row.get("redelivered_at"))
                        .as("and it is outstanding again, not still marked as dealt with")
                        .isNull();
                assertThat(jdbc.queryForObject(
                                "select count(*) from dead_letter", Long.class))
                        .isEqualTo(1);
                return;
            }
            sleep(Duration.ofMillis(200));
        }
        throw new AssertionError("it never came back");
    }

    // -- producing --------------------------------------------------------------------------

    /** A well formed event whose payload this handler cannot make sense of. */
    private static String capturedIn(UUID eventId, UUID payment, String currency) {
        return captured(eventId, payment, UUID.randomUUID(), "order-bad-currency")
                .replace("\"currency\":\"TRY\"", "\"currency\":\"" + currency + "\"");
    }

    private static String captured(UUID eventId, UUID payment, UUID merchant, String reference) {
        return """
                {"eventId":"%s","type":"payment.captured","version":1,"aggregateType":"payment",
                 "aggregateId":"%s","merchantId":"%s","occurredAt":"%s",
                 "correlationId":"a-request-somewhere",
                 "payload":{"paymentId":"%s","merchantId":"%s","reference":"%s","amount":125000,
                   "currency":"TRY","acquirerReference":"auth_z","ledgerEntryId":"%s","at":"%s"}}
                """
                .formatted(
                        eventId, payment, merchant, Instant.now(),
                        payment, merchant, reference, UUID.randomUUID(), Instant.now());
    }

    private void publish(String key, String message) {
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

    private Map<String, Object> eventuallyOneDeadLetter() {
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < giveUpAt) {
            List<Map<String, Object>> rows = jdbc.queryForList("select * from dead_letter");
            if (!rows.isEmpty()) {
                return rows.getFirst();
            }
            sleep(Duration.ofMillis(250));
        }
        throw new AssertionError("nothing was ever set aside");
    }

    private Map<String, Object> eventuallyNotified(UUID payment) {
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < giveUpAt) {
            List<Map<String, Object>> found = notificationsFor(payment);
            if (!found.isEmpty()) {
                return found.getFirst();
            }
            sleep(Duration.ofMillis(250));
        }
        throw new AssertionError("nothing was ever said about payment " + payment);
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
