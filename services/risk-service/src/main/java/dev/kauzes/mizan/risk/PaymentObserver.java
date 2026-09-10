package dev.kauzes.mizan.risk;

import dev.kauzes.mizan.common.web.inbox.Inbox;
import dev.kauzes.mizan.common.web.inbox.ReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Learns what is normal from what has happened.
 *
 * <p>Events, not the payment database. Risk knowing what a payment was is a fact it is told,
 * not one it goes and reads: the boundary is the reason these are separate services, and a
 * scorer that reached across it would make the payment service unable to change its schema
 * without breaking fraud detection.
 *
 * <p>Through the same inbox as every other consumer, so an event delivered twice is learned
 * from once. That matters more here than almost anywhere: a baseline that counted the same
 * payment three times because the topic hiccuped is a baseline that has quietly moved.
 */
@Component
public class PaymentObserver {

    private static final Logger log = LoggerFactory.getLogger(PaymentObserver.class);

    /**
     * A constant, not a class name.
     *
     * <p>Renaming the class would otherwise make every event it has already learned from look
     * unlearned, and it would learn from all of them again — doubling the baseline it spent
     * months building.
     */
    private static final String HANDLER = "payment-observer";

    private final Inbox inbox;
    private final ObservedBehaviour observed;

    public PaymentObserver(Inbox inbox, ObservedBehaviour observed) {
        this.inbox = inbox;
        this.observed = observed;
    }

    @KafkaListener(
            topics = "mizan.payment.events",
            groupId = "risk-service",
            id = "payment-observer")
    public void onPaymentEvent(String message) {
        inbox.once(HANDLER, message, this::learnFrom);
    }

    /**
     * Runs inside the transaction that records the event as handled, so what was learned and
     * the record of having learned it cannot come apart.
     */
    private void learnFrom(ReceivedEvent event) {
        String outcome = switch (event.type()) {
            // What a merchant normally takes is what actually got taken. An authorization is a
            // promise and a capture is the money, and using authorizations would let a burst
            // of attempts that were never captured move what the platform thinks is normal.
            case "payment.captured" -> "APPROVED";
            // A decline says nothing about the merchant's ordinary business and a great deal
            // about the card, which is why it is recorded and kept out of the baseline.
            case "payment.declined" -> "DECLINED";
            default -> null;
        };

        if (outcome == null) {
            // Recorded as handled anyway, so it is not reconsidered on every redelivery for as
            // long as the topic keeps it.
            log.debug("nothing to learn from {}", event.type());
            return;
        }

        observed.record(
                event.eventId(),
                event.aggregateId(),
                event.merchantId(),
                event.number("amount"),
                event.text("currency"),
                // The payment service publishes four digits, not a card. Enough to recognise
                // the same card across payments here, and not enough to be one.
                fingerprintOf(event),
                null,
                outcome,
                event.occurredAt());
    }

    /**
     * What this service uses to recognise a card across payments.
     *
     * <p>The last four digits are all the payment service keeps and all it publishes, so they
     * are all there is to work with. Four digits are shared by one card in ten thousand, which
     * makes this a weaker signal than a real fingerprint and an honest one: it is derived from
     * what actually crosses the wire rather than from a field somebody hoped would be there.
     *
     * <p>Scoped to the merchant everywhere it is used, so the collisions that matter are
     * collisions within one merchant's own customers rather than across the platform.
     */
    private static String fingerprintOf(ReceivedEvent event) {
        String lastFour = event.text("cardLastFour");
        return lastFour == null || lastFour.isBlank() ? null : "last4:" + lastFour;
    }
}
