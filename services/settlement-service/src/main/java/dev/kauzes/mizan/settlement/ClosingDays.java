package dev.kauzes.mizan.settlement;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * When a day gets closed, and the one way a person can close one by hand.
 *
 * <p>An actuator endpoint rather than an API route, for the same reason the ledger's integrity
 * check and MIZ-53's stuck payments are: closing a day is a question about the platform rather
 * than about one merchant's data, and no merchant should be able to decide when they get paid.
 *
 * <p>The sweep waits an hour after a day has ended before closing it. Captures arrive as
 * events, asynchronously, and a close that ran at midnight exactly would settle a day that
 * this service had not finished hearing about — leaving payments stranded in a day they did
 * not happen in, which is the one thing the batch model cannot express.
 */
@Component
@Endpoint(id = "settlements")
public class ClosingDays {

    private static final Logger log = LoggerFactory.getLogger(ClosingDays.class);

    private final Settlement settlement;
    private final Payouts payouts;
    private final JdbcTemplate jdbc;
    private final ZoneId zone;
    private final Duration closeAfter;

    public ClosingDays(
            Settlement settlement,
            Payouts payouts,
            JdbcTemplate jdbc,
            @Value("${mizan.settlement.zone:Europe/Istanbul}") String zone,
            @Value("${mizan.settlement.close-after:1h}") Duration closeAfter) {

        this.settlement = settlement;
        this.payouts = payouts;
        this.jdbc = jdbc;
        this.zone = ZoneId.of(zone);
        this.closeAfter = closeAfter;
    }

    /**
     * Closes every day that has ended long enough ago and still has something waiting.
     *
     * <p>Every such day, not only yesterday. A service that was down for three days would
     * otherwise leave three days unsettled forever, and nobody would notice until a merchant
     * asked where their money was.
     */
    @Scheduled(
            fixedDelayString = "${mizan.settlement.close-every:15m}",
            initialDelayString = "${mizan.settlement.close-every:15m}")
    public void closeWhatIsDue() {
        LocalDate closeable = LocalDate.ofInstant(Instant.now().minus(closeAfter), zone);

        List<LocalDate> days = jdbc.queryForList(
                """
                select distinct settled_for
                from settleable
                where batch_id is null and settled_for < ?
                order by settled_for
                """,
                LocalDate.class,
                closeable);

        for (LocalDate day : days) {
            settlement.closeDay(day);
        }

        // After closing, not during it. A ledger that is briefly unreachable must not stop a
        // day being settled — the batch is a decision about payments and the fee is a
        // movement of money — and this finishes every batch that is still waiting for one,
        // not only the ones this pass just made.
        payouts.recordWhatIsNotYetInTheBooks();
    }

    @ReadOperation
    public Map<String, Object> whatIsWaiting() {
        List<Map<String, Object>> waiting = jdbc.queryForList(
                """
                select settled_for, currency, count(*) as payments, sum(amount) as captured
                from settleable
                where batch_id is null
                group by settled_for, currency
                order by settled_for, currency
                """);

        return Map.of(
                "waiting", waiting,
                "closedBatches",
                jdbc.queryForObject("select count(*) from settlement_batch", Long.class));
    }

    /**
     * Closes one day now, because an operator asked.
     *
     * <p>Repeatable on purpose: asking twice answers with the batches that exist rather than
     * making a second set. That is what makes this safe to hand to somebody during an
     * incident, which is the only time anybody uses it.
     */
    @WriteOperation
    public Map<String, Object> close(@Selector String day) {
        LocalDate settling = LocalDate.parse(day);
        List<Settlement.Closed> closed = settlement.closeDay(settling);
        int recorded = payouts.recordWhatIsNotYetInTheBooks();

        if (closed.isEmpty() && recorded == 0) {
            log.info("nothing was waiting to be settled for {}", settling);
        }

        return Map.of(
                "day", settling.toString(),
                "closed", closed.stream().map(Settlement.Closed::asAnswer).toList(),
                "feesRecorded", recorded);
    }

    /** What a merchant has waiting, for an operator answering a question about one. */
    public long waitingFor(UUID merchantId) {
        Long counted = jdbc.queryForObject(
                "select count(*) from settleable where merchant_id = ? and batch_id is null",
                Long.class,
                merchantId);
        return counted == null ? 0 : counted;
    }
}
