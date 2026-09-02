package dev.kauzes.mizan.common.web.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Puts an event on a topic.
 *
 * <p>The message is the whole envelope with the payload nested inside it, so a consumer
 * receives one document that says what happened, to what, when, and because of which request.
 * The payload is spliced in as it was stored rather than deserialised and serialised again:
 * a round trip through this process could only change what the consumer receives, and there is
 * nothing here that needs to understand it.
 *
 * <p>The routing fields are repeated as headers. A consumer deciding whether it cares about a
 * message, or a dead letter viewer listing what is stuck, should not have to parse a body to
 * find out what it is looking at.
 */
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final Duration timeout;

    public KafkaEventPublisher(
            KafkaTemplate<String, String> kafka, ObjectMapper json, Duration timeout) {

        this.kafka = kafka;
        this.json = json;
        this.timeout = timeout;
    }

    @Override
    public void publish(PendingEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.topic(), event.key(), message(event));

        header(record, "event-id", event.eventId().toString());
        header(record, "event-type", event.type());
        header(record, "event-version", String.valueOf(event.version()));
        header(record, "merchant-id", event.merchantId().toString());
        if (event.correlationId() != null && !event.correlationId().isBlank()) {
            header(record, "correlation-id", event.correlationId());
        }

        try {
            // Waited on rather than left to a callback. The relay marks the row published
            // when this returns, so returning before the broker has the event would record
            // as delivered something still sitting in a buffer in this process.
            kafka.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing " + event.eventId());
        } catch (ExecutionException | TimeoutException notAcknowledged) {
            // A timeout is not proof it did not arrive, which is exactly why the consumer
            // side has to be idempotent. Retrying is the only safe thing to do with it.
            throw new IllegalStateException(
                    "the broker did not acknowledge " + event.eventId(), notAcknowledged);
        }
    }

    private String message(PendingEvent event) {
        ObjectNode message = json.createObjectNode();
        message.put("eventId", event.eventId().toString());
        message.put("type", event.type());
        message.put("version", event.version());
        message.put("aggregateType", event.aggregateType());
        message.put("aggregateId", event.aggregateId().toString());
        message.put("merchantId", event.merchantId().toString());
        message.put("occurredAt", event.occurredAt().toString());
        message.put("correlationId", event.correlationId());
        message.set("payload", json.readTree(event.payload()));
        return message.toString();
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
    }
}
