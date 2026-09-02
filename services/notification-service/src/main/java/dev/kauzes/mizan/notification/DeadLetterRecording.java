package dev.kauzes.mizan.notification;

import dev.kauzes.mizan.common.web.inbox.DeadLetters;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the dead letter topic into the table an operator can query.
 *
 * <p>This listener must not be able to fail in the way the one it exists for did. It writes a
 * row and nothing else: no parsing that could throw on a payload that is already known to be
 * malformed, no work that depends on anything outside this database. A dead letter handler
 * that dead letters is a hole with no bottom.
 */
@Component
public class DeadLetterRecording {

    private final DeadLetters deadLetters;
    private final ObjectMapper json;

    public DeadLetterRecording(DeadLetters deadLetters, ObjectMapper json) {
        this.deadLetters = deadLetters;
        this.json = json;
    }

    @KafkaListener(
            topics = "mizan.payment.events.dead-letter",
            groupId = "notification-service-dead-letters",
            id = "payment-dead-letters")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        deadLetters.record(new DeadLetters.DeadLetter(
                null,
                eventIdOf(record),
                typeOf(record),
                PaymentNotifications.HANDLER,
                header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC, "mizan.payment.events"),
                intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                record.key(),
                reasonFrom(record),
                correlationOf(record),
                record.value(),
                0,
                null,
                null));
    }

    /**
     * The event's own id, from the header the producer set, falling back to the body.
     *
     * <p>Read from the header first because the body may be exactly what could not be parsed,
     * and this listener has to work on a message that nothing else could.
     */
    private UUID eventIdOf(ConsumerRecord<String, String> record) {
        String fromHeader = header(record, "event-id", null);
        if (fromHeader != null) {
            return UUID.fromString(fromHeader);
        }
        try {
            JsonNode body = json.readTree(record.value());
            return UUID.fromString(body.path("eventId").asString());
        } catch (RuntimeException unreadable) {
            // Something must identify the row, and a message with no id is exactly the sort
            // of thing that ends up here. A generated one still lets an operator see it,
            // count it and read its payload.
            return UUID.randomUUID();
        }
    }

    /**
     * The request this event came from, from the header or from the body.
     *
     * <p>The fallback is not defensive padding. A message redelivered by an operator is
     * republished from the payload alone and carries no headers, so a dead letter that failed
     * a second time would otherwise lose the very thing that makes it traceable — at exactly
     * the point somebody is trying to work out what is wrong with it.
     */
    private String correlationOf(ConsumerRecord<String, String> record) {
        String fromHeader = header(record, "correlation-id", null);
        if (fromHeader != null) {
            return fromHeader;
        }
        try {
            String fromBody = json.readTree(record.value()).path("correlationId").asString();
            return fromBody.isBlank() ? null : fromBody;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private String typeOf(ConsumerRecord<String, String> record) {
        String fromHeader = header(record, "event-type", null);
        if (fromHeader != null) {
            return fromHeader;
        }
        try {
            return json.readTree(record.value()).path("type").asString();
        } catch (RuntimeException unreadable) {
            return "unknown";
        }
    }

    /**
     * Why it failed, as the exception and its message.
     *
     * <p>Spring puts a whole stack trace on one of these headers. The first line is what
     * somebody reads; the rest is a text column nobody does.
     */
    private static String reasonFrom(ConsumerRecord<String, String> record) {
        String type = header(record, KafkaHeaders.DLT_EXCEPTION_FQCN, "");
        String message = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE, "");
        String reason = (type.isEmpty() ? "" : shortName(type) + ": ") + message;
        if (reason.isBlank()) {
            reason = "no reason was recorded, which is itself worth looking into";
        }
        return reason.length() > 2000 ? reason.substring(0, 2000) : reason;
    }

    private static String shortName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? className : className.substring(lastDot + 1);
    }

    private static String header(
            ConsumerRecord<String, String> record, String name, String fallback) {

        Header header = record.headers().lastHeader(name);
        return header == null ? fallback : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Integer intHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value().length < 4) {
            return null;
        }
        return java.nio.ByteBuffer.wrap(header.value()).getInt();
    }

    private static Long longHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value().length < 8) {
            return null;
        }
        return java.nio.ByteBuffer.wrap(header.value()).getLong();
    }
}
