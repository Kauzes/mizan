package dev.kauzes.mizan.common.web.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * An event as it sits in the table, waiting to be published.
 *
 * <p>The payload is JSON text rather than an object, because nothing between here and the
 * broker needs to understand it. Deserialising it into something and serialising it again
 * would be work whose only possible outcome is changing what a consumer receives.
 *
 * @param sequence the total order this event was written in, and what the relay publishes by
 * @param attempts how many times publishing this has been tried and failed
 */
public record PendingEvent(
        UUID eventId,
        String type,
        int version,
        String aggregateType,
        UUID aggregateId,
        UUID merchantId,
        Instant occurredAt,
        String correlationId,
        String payload,
        long sequence,
        int attempts) {

    /** The partition key: everything about one aggregate goes to one partition, in order. */
    public String key() {
        return aggregateId.toString();
    }

    public String topic() {
        return Topics.of(aggregateType);
    }
}
