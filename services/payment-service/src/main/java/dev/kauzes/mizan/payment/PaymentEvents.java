package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.common.web.outbox.DomainEvent;
import dev.kauzes.mizan.common.web.outbox.EventType;
import dev.kauzes.mizan.common.web.outbox.Outbox;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Everything this service tells the rest of the platform, and nothing else.
 *
 * <p>A list rather than strings at call sites, because a published event is a promise to
 * whoever consumes it and promises should not appear by accident. Adding one here is a
 * decision somebody makes on purpose; there is nowhere else to add one.
 *
 * <p>What is <em>not</em> here is as deliberate. A payment being created announces nothing:
 * nobody was contacted, no money moved, and an event for it would be this service narrating
 * its own database. The events below are the four moments where something outside this
 * service became true.
 *
 * <p>Nothing is published from here. The event is written to a table in the same transaction
 * as the change that caused it, and publishing is MIZ-48's problem.
 */
@Component
public class PaymentEvents {

    /**
     * The payloads.
     *
     * <p>Records written for consumers, not the {@link Payment} entity handed to a
     * serialiser. An entity serialised is a published contract that changes whenever somebody
     * renames a column, and the break shows up in a different repository weeks later.
     *
     * <p>None of them carries a card number. Only the last four digits exist anywhere in this
     * service, which is what makes that easy to keep true rather than a rule to remember.
     */
    public record Authorized(
            UUID paymentId,
            UUID merchantId,
            long amount,
            String currency,
            String reference,
            String acquirerReference,
            String cardLastFour,
            Instant at) {
    }

    public record Declined(
            UUID paymentId,
            UUID merchantId,
            long amount,
            String currency,
            String reference,
            String acquirerReference,
            String cardLastFour,
            /** The acquirer's own word for it. A merchant will be asked why, by a person. */
            String reason,
            Instant at) {
    }

    public record Captured(
            UUID paymentId,
            UUID merchantId,
            long amount,
            String currency,
            String reference,
            String acquirerReference,
            /** Where in the books this landed, so a consumer need not ask this service. */
            UUID ledgerEntryId,
            Instant at) {
    }

    public record Voided(
            UUID paymentId,
            UUID merchantId,
            long amount,
            String currency,
            String reference,
            String acquirerReference,
            String reason,
            Instant at) {
    }

    /** The closed set. */
    public enum Type implements EventType {
        AUTHORIZED("payment.authorized"),
        DECLINED("payment.declined"),
        CAPTURED("payment.captured"),
        VOIDED("payment.voided");

        private final String type;

        Type(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public int version() {
            // One, and it stays one until a payload changes in a way a consumer could not
            // survive. A field added is not that; a field removed or given a new meaning is.
            return 1;
        }

        @Override
        public String aggregateType() {
            return "payment";
        }
    }

    private final Outbox outbox;

    public PaymentEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    /**
     * Records what a payment just became.
     *
     * <p>Takes the payment rather than a payload so that a caller cannot record an event
     * that disagrees with the row it is about. It must be called from inside the transaction
     * that made the change, which the outbox itself insists on.
     */
    public void record(Payment payment, String reason) {
        switch (payment.status()) {
            case AUTHORIZED -> write(Type.AUTHORIZED, payment, new Authorized(
                    payment.id(),
                    payment.merchantId(),
                    payment.money().amount(),
                    payment.money().currency().getCurrencyCode(),
                    payment.reference(),
                    payment.acquirerReference(),
                    payment.cardLastFour(),
                    payment.updatedAt()));

            case DECLINED -> write(Type.DECLINED, payment, new Declined(
                    payment.id(),
                    payment.merchantId(),
                    payment.money().amount(),
                    payment.money().currency().getCurrencyCode(),
                    payment.reference(),
                    payment.acquirerReference(),
                    payment.cardLastFour(),
                    payment.declineReason(),
                    payment.updatedAt()));

            case CAPTURED -> write(Type.CAPTURED, payment, new Captured(
                    payment.id(),
                    payment.merchantId(),
                    payment.money().amount(),
                    payment.money().currency().getCurrencyCode(),
                    payment.reference(),
                    payment.acquirerReference(),
                    payment.ledgerEntryId(),
                    payment.updatedAt()));

            case VOIDED -> write(Type.VOIDED, payment, new Voided(
                    payment.id(),
                    payment.merchantId(),
                    payment.money().amount(),
                    payment.money().currency().getCurrencyCode(),
                    payment.reference(),
                    payment.acquirerReference(),
                    reason,
                    payment.updatedAt()));

            // Announcing an intent would be this service narrating its own database, and
            // not knowing yet is not something to tell anybody: it is a question, and MIZ-44
            // answers it within seconds by asking the acquirer. The answer is what gets an
            // event.
            case CREATED, AUTHORIZATION_UNKNOWN -> {
            }
        }
    }

    private void write(Type type, Payment payment, Object payload) {
        outbox.record(DomainEvent.of(type, payment.id(), payment.merchantId(), payload));
    }
}
