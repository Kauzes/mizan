package dev.kauzes.mizan.common.web.outbox;

import java.sql.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Where an event waits until somebody publishes it.
 *
 * <p>The whole point is the word <em>transaction</em>. This writes through the same
 * {@link JdbcTemplate} the caller's transaction is already using, so the row lands inside
 * whatever transaction is open when it is called. Change the payment and record the event in
 * one method and they commit together or roll back together — there is no arrangement of
 * failures that leaves one without the other.
 *
 * <p>Publishing to a broker inside that transaction would not have that property, which is
 * the reason this table exists at all. A broker cannot join a database transaction: publish
 * first and a rollback leaves an announcement of something that did not happen; publish after
 * committing and a crash in between leaves money moved that nobody was told about. Both are
 * silent, and both are found by a person reconciling by hand. Writing a row is the only step
 * that can be made atomic with the change, so it is the only step taken here.
 *
 * <p>Nothing in this class knows about Kafka, and it should stay that way. MIZ-48 drains the
 * table.
 *
 * <p>Plain SQL against a table each service creates in its own migration, for the same reason
 * {@code IdempotencyStore} is: an entity in a shared module has to be scanned into every
 * service's persistence unit, which is configuration that has to be right in six places
 * instead of none.
 */
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Outbox(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Records that something happened, in the caller's transaction.
     *
     * <p>Must be called from inside one. A call with no transaction open would commit on its
     * own, which is exactly the failure this exists to prevent, so it refuses rather than
     * quietly doing the wrong thing.
     */
    public void record(DomainEvent event) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "an event has to be recorded in the transaction that caused it, and there "
                            + "is no transaction here. Recording it anyway would commit an "
                            + "announcement of something that may yet be rolled back.");
        }

        jdbc.update(
                "insert into outbox_event (id, type, version, aggregate_type, aggregate_id, "
                        + "merchant_id, occurred_at, correlation_id, payload) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                event.eventId(),
                event.type(),
                event.version(),
                event.aggregateType(),
                event.aggregateId(),
                event.merchantId(),
                Timestamp.from(event.occurredAt()),
                event.correlationId(),
                json.writeValueAsString(event.payload()));

        log.debug(
                "recorded {} {} for {} {}",
                event.type(),
                event.eventId(),
                event.aggregateType(),
                event.aggregateId());
    }
}
