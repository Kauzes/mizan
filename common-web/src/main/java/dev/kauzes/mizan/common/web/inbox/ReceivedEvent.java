package dev.kauzes.mizan.common.web.inbox;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * An event as it arrived, envelope read and payload left alone.
 *
 * <p>The payload stays a {@link JsonNode} rather than being bound to a type here, because the
 * envelope is what every consumer understands and the payload is what only the handler for
 * that type does. Binding it up front would mean this class knowing every payload every
 * producer will ever publish.
 */
public record ReceivedEvent(
        UUID eventId,
        String type,
        int version,
        String aggregateType,
        UUID aggregateId,
        UUID merchantId,
        Instant occurredAt,
        String correlationId,
        JsonNode payload) {

    /** Reads the envelope. A message that is not one is a problem for the caller, not here. */
    public static ReceivedEvent from(JsonNode message) {
        return new ReceivedEvent(
                UUID.fromString(message.path("eventId").asString()),
                message.path("type").asString(),
                message.path("version").asInt(),
                message.path("aggregateType").asString(),
                UUID.fromString(message.path("aggregateId").asString()),
                UUID.fromString(message.path("merchantId").asString()),
                Instant.parse(message.path("occurredAt").asString()),
                message.path("correlationId").asString(),
                message.path("payload"));
    }

    /** Whether this is one of the types a handler cares about. */
    public boolean isOneOf(String... types) {
        for (String candidate : types) {
            if (candidate.equals(type)) {
                return true;
            }
        }
        return false;
    }

    public String text(String field) {
        return payload.path(field).asString();
    }

    public long number(String field) {
        return payload.path(field).asLong();
    }
}
