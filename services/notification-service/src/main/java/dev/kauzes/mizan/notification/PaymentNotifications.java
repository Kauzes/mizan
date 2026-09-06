package dev.kauzes.mizan.notification;

import dev.kauzes.mizan.common.web.inbox.Inbox;
import dev.kauzes.mizan.common.web.inbox.ReceivedEvent;
import dev.kauzes.mizan.notification.webhook.WebhookDeliveries;
import dev.kauzes.mizan.notification.webhook.WebhookEndpoint;
import dev.kauzes.mizan.notification.webhook.WebhookEndpointRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The first thing on this platform that listens rather than answers.
 *
 * <p>It turns what happened to a payment into something a merchant should be told. Deciding
 * that is all it does: sending it — signed, retried, dead lettered — is Epic 8, and will read
 * the table this writes rather than the topic, so that the decision is made once.
 *
 * <p>Everything it does goes through the inbox, so an event delivered twice produces one
 * notification. That is not a precaution: MIZ-48 publishes at least once by design, so
 * redelivery is the system working normally, and a handler that was not built for it would
 * send a customer two receipts on an ordinary restart.
 */
@Component
public class PaymentNotifications {

    private static final Logger log = LoggerFactory.getLogger(PaymentNotifications.class);

    /**
     * The name this handler claims events under.
     *
     * <p>A constant rather than a class name: renaming the class would otherwise make every
     * event it has already handled look unhandled, and it would do all of them again.
     */
    static final String HANDLER = "payment-notifications";

    private final Inbox inbox;
    private final JdbcTemplate jdbc;
    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveries deliveries;
    private final ObjectMapper json;

    public PaymentNotifications(
            Inbox inbox,
            JdbcTemplate jdbc,
            WebhookEndpointRepository endpoints,
            WebhookDeliveries deliveries,
            ObjectMapper json) {

        this.inbox = inbox;
        this.jdbc = jdbc;
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.json = json;
    }

    @KafkaListener(
            topics = "mizan.payment.events",
            groupId = "notification-service",
            id = "payment-notifications")
    public void onPaymentEvent(String message) {
        inbox.once(HANDLER, message, this::decideWhatToSay);
    }

    /**
     * Runs inside the transaction that records the event as handled, so the notification and
     * the record of having made it cannot come apart.
     */
    private void decideWhatToSay(ReceivedEvent event) {
        switch (event.type()) {
            case "payment.captured" -> write(
                    event,
                    "PAYMENT_CAPTURED",
                    "You have been paid " + money(event) + " for " + event.text("reference") + ".");

            case "payment.declined" -> write(
                    event,
                    "PAYMENT_DECLINED",
                    "A payment of " + money(event) + " for " + event.text("reference")
                            + " was declined: " + event.text("reason") + ".");

            case "payment.voided" -> write(
                    event,
                    "PAYMENT_VOIDED",
                    "The reservation of " + money(event) + " for " + event.text("reference")
                            + " was released.");

            // An authorization is a promise that money is there, not money arriving. There is
            // nothing to tell a merchant yet, and the event is still recorded as handled so
            // that it is not reconsidered on every redelivery.
            default -> log.debug("nothing to say about {}", event.type());
        }
    }

    private void write(ReceivedEvent event, String kind, String message) {
        UUID notification = UUID.randomUUID();
        jdbc.update(
                "insert into notification (id, merchant_id, payment_id, kind, message, "
                        + "caused_by, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                notification,
                event.merchantId(),
                event.aggregateId(),
                kind,
                message,
                event.eventId(),
                Timestamp.from(Instant.now()));

        // In the same transaction as the decision, which is the outbox lesson from the other
        // side: a notification with no deliveries is a merchant who is never told, and a
        // delivery with no notification is a merchant told about something this platform does
        // not believe. Both commit or neither does.
        queueDeliveries(event, notification, message);

        log.info("{} for payment {}", kind, event.aggregateId());
    }

    /**
     * Queues one delivery per endpoint that asked for this type.
     *
     * <p>The body is built once, here, and stored. Rebuilding it per attempt would let a
     * retry carry a signature for something subtly different — a field order, a timestamp —
     * and a merchant checking properly would reject it.
     */
    private void queueDeliveries(ReceivedEvent event, UUID notification, String message) {
        for (WebhookEndpoint endpoint : endpoints.wanting(event.merchantId(), event.type())) {
            UUID delivery = UUID.randomUUID();
            deliveries.queue(
                    delivery,
                    event.merchantId(),
                    endpoint.id(),
                    notification,
                    event.aggregateId(),
                    event.type(),
                    bodyFor(delivery, event, message));
        }
    }

    /**
     * What a merchant receives.
     *
     * <p>The delivery's own id is in the body as well as in a header, because a merchant
     * storing what they received should be able to deduplicate from the thing they stored
     * rather than from a header they may not have kept.
     */
    private String bodyFor(UUID delivery, ReceivedEvent event, String message) {
        ObjectNode body = json.createObjectNode();
        body.put("id", delivery.toString());
        body.put("type", event.type());
        body.put("eventId", event.eventId().toString());
        body.put("merchantId", event.merchantId().toString());
        body.put("occurredAt", event.occurredAt().toString());
        body.put("summary", message);
        body.set("data", event.payload());
        return body.toString();
    }

    /**
     * Minor units into something a person reads.
     *
     * <p>The currency decides how many decimal places there are; assuming two is how a
     * platform tells a Japanese merchant they have been paid a hundred times too little.
     */
    private static String money(ReceivedEvent event) {
        Currency currency = Currency.getInstance(event.text("currency"));
        int places = Math.max(currency.getDefaultFractionDigits(), 0);
        java.math.BigDecimal amount =
                java.math.BigDecimal.valueOf(event.number("amount"), places);
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
