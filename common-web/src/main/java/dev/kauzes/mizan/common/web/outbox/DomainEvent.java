package dev.kauzes.mizan.common.web.outbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Something that happened, in a shape another service can rely on.
 *
 * <p>The envelope is separate from the payload on purpose. Everything a consumer needs in
 * order to route, deduplicate, order and trace an event is here, in fields whose meaning does
 * not change between one event type and the next — so a relay, a dead letter viewer or an
 * idempotent consumer can be written once rather than per type. The payload is where the
 * meaning lives, and only the handler for that type looks inside it.
 *
 * <p>A payload is a deliberate record, never an entity handed to a serialiser. An entity
 * serialised is a published contract that changes whenever somebody renames a column, and the
 * change is invisible until a consumer in another repository breaks.
 *
 * @param eventId what a consumer deduplicates on. Generated here, so it exists before the
 *     event is written and survives every redelivery of it
 * @param type what happened, from the closed set the producing service publishes
 * @param version the shape of the payload, so a consumer that predates a change can say so
 *     rather than guess
 * @param aggregateType what kind of thing this happened to
 * @param aggregateId which one. Also the ordering key: events about one payment stay in
 *     order relative to each other, which is the only ordering a partitioned log can honestly
 *     offer
 * @param merchantId whose it is. On the envelope rather than only in the payload, because
 *     filtering by tenant should not require understanding the type
 * @param occurredAt when the thing happened, which is not when anybody published it
 * @param correlationId the request that caused this, so a trace crosses the gap between a
 *     call and everything that happened because of it
 */
public record DomainEvent(
        UUID eventId,
        String type,
        int version,
        String aggregateType,
        UUID aggregateId,
        UUID merchantId,
        Instant occurredAt,
        String correlationId,
        Object payload) {

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
    }

    /**
     * A new event, now.
     *
     * <p>The correlation id is taken from whatever request is being served rather than passed
     * in, because an event recorded on a code path that forgot to thread it through is an
     * event nobody can trace back, and forgetting is the normal case.
     */
    public static DomainEvent of(
            EventType type, UUID aggregateId, UUID merchantId, Object payload) {

        return new DomainEvent(
                UUID.randomUUID(),
                type.type(),
                type.version(),
                type.aggregateType(),
                aggregateId,
                merchantId,
                // Micros, because that is what Postgres keeps, and an instant that changes
                // when it makes a round trip is a thing tests have already caught once here.
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                dev.kauzes.mizan.common.correlation.CorrelationContext.currentOrEmpty(),
                payload);
    }
}
