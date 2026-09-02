package dev.kauzes.mizan.notification;

import dev.kauzes.mizan.common.web.inbox.Inbox;
import dev.kauzes.mizan.common.web.inbox.ReceivedEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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

    public PaymentNotifications(Inbox inbox, JdbcTemplate jdbc) {
        this.inbox = inbox;
        this.jdbc = jdbc;
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
        jdbc.update(
                "insert into notification (id, merchant_id, payment_id, kind, message, "
                        + "caused_by, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                event.merchantId(),
                event.aggregateId(),
                kind,
                message,
                event.eventId(),
                Timestamp.from(Instant.now()));

        log.info("{} for payment {}", kind, event.aggregateId());
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
