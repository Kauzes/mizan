package dev.kauzes.mizan.settlement;

import dev.kauzes.mizan.common.web.inbox.Inbox;
import dev.kauzes.mizan.common.web.inbox.ReceivedEvent;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * What has been captured and is therefore owed to somebody.
 *
 * <p>Learned from events, like everything else this service knows. Settlement never reads the
 * payment database: a service that reached across would make the payment service unable to
 * change a column without breaking the money going out of the door.
 *
 * <p>Through the shared inbox, so a redelivered capture is settled once. That matters here
 * more than in most places — a capture counted twice is a merchant paid twice, and the second
 * payment is somebody else's money.
 */
@Component
public class CapturedPayments {

    private static final Logger log = LoggerFactory.getLogger(CapturedPayments.class);

    /**
     * A constant, not a class name.
     *
     * <p>Renaming the class would otherwise make every capture it has already recorded look
     * unrecorded, and it would record them all again — settling a year of payments twice.
     */
    private static final String HANDLER = "captured-payments";

    private final Inbox inbox;
    private final JdbcTemplate jdbc;
    private final ZoneId zone;

    public CapturedPayments(
            Inbox inbox,
            JdbcTemplate jdbc,
            @Value("${mizan.settlement.zone:Europe/Istanbul}") String zone) {

        this.inbox = inbox;
        this.jdbc = jdbc;
        this.zone = ZoneId.of(zone);
    }

    @KafkaListener(
            topics = "mizan.payment.events",
            groupId = "settlement-service",
            id = "captured-payments")
    public void onPaymentEvent(String message) {
        inbox.once(HANDLER, message, this::record);
    }

    /**
     * Records a capture as something to be settled.
     *
     * <p>Captures only. An authorization is a promise that the money is there and settlement
     * is about money that moved, so settling an authorization would be paying a merchant for
     * a payment that might still be voided.
     *
     * <p>Refunds are deliberately not netted in here. Money going back has its own timing and
     * its own movement in the books, and folding it into a settlement total is how a merchant
     * loses the ability to see either — MIZ-70 is where the two meet.
     */
    private void record(ReceivedEvent event) {
        if (!event.isOneOf("payment.captured")) {
            log.debug("nothing to settle from {}", event.type());
            return;
        }

        long amount = event.payload().path("amount").asLong();
        String currency = event.text("currency");
        // Which day this belongs to comes from when the money moved, not from when this
        // service heard about it. A consumer that was down for an hour must not shift
        // payments into a day they did not happen in.
        LocalDate day = LocalDate.ofInstant(event.occurredAt(), zone);

        int written = jdbc.update(
                """
                insert into settleable (payment_id, merchant_id, amount, currency,
                    captured_at, settled_for, acquirer_reference)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (payment_id) do nothing
                """,
                event.aggregateId(),
                event.merchantId(),
                amount,
                currency,
                Timestamp.from(event.occurredAt()),
                day,
                event.text("acquirerReference"));

        if (written == 0) {
            // The inbox should have caught this, and a payment captured twice would be a
            // larger problem than a duplicate row. Said out loud rather than ignored.
            log.warn("payment {} was already waiting to be settled", event.aggregateId());
            return;
        }

        log.info(
                "payment {} of {} {} is waiting to be settled for {}",
                event.aggregateId(),
                amount,
                currency,
                day);
    }

    /** Which day a moment belongs to, for anything that has to agree with this service. */
    public LocalDate dayOf(java.time.Instant moment) {
        return LocalDate.ofInstant(moment, zone);
    }

    /** What is waiting, for an operator who wants to know before a close runs. */
    public long waiting(UUID merchantId) {
        Long counted = jdbc.queryForObject(
                "select count(*) from settleable where merchant_id = ? and batch_id is null",
                Long.class,
                merchantId);
        return counted == null ? 0 : counted;
    }
}
