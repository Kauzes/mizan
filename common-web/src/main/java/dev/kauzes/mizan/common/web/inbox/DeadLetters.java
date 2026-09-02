package dev.kauzes.mizan.common.web.inbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Events that could not be handled, kept where a person can find them.
 *
 * <p>A dead letter topic alone satisfies the machine and not the person: it stops a poison
 * message blocking its partition, and it leaves the evidence somewhere nobody looks until
 * they know to. What an operator actually needs to ask is "what is broken, why, and how much
 * of it", and that is a query.
 *
 * <p>So the dead letter topic is consumed into this table. Both exist for a reason — the topic
 * is what unblocks delivery at the moment of failure, and the table is what makes the failure
 * legible afterwards and redeliverable once the bug is fixed.
 */
public class DeadLetters {

    private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);

    /**
     * One event that could not be handled.
     *
     * @param payload the original message, byte for byte as it was published. Not a summary:
     *     redelivering a reconstruction would redeliver this platform's idea of the event
     *     rather than the event
     */
    public record DeadLetter(
            UUID id,
            UUID eventId,
            String type,
            String handler,
            String topic,
            Integer partition,
            Long offset,
            /** The key it was published under, so a redelivery lands in the same partition. */
            String messageKey,
            String reason,
            String correlationId,
            String payload,
            int attempts,
            Instant firstFailedAt,
            Instant redeliveredAt) {
    }

    private final JdbcTemplate jdbc;

    public DeadLetters(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records one, or notes that it has happened again.
     *
     * <p>Keyed on the event and the handler, so an event that dead letters twice — because
     * somebody redelivered it before the bug was actually fixed — is one row with a count
     * rather than a growing pile that hides how many distinct things are wrong.
     */
    public void record(DeadLetter letter) {
        int updated = jdbc.update(
                "update dead_letter set attempts = attempts + 1, reason = ?, "
                        + "redelivered_at = null, last_failed_at = ? "
                        + "where event_id = ? and handler = ?",
                letter.reason(),
                Timestamp.from(Instant.now()),
                letter.eventId(),
                letter.handler());

        if (updated == 0) {
            jdbc.update(
                    "insert into dead_letter (id, event_id, type, handler, topic, partition, "
                            + "\"offset\", message_key, reason, correlation_id, payload, "
                            + "attempts, first_failed_at, last_failed_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                    UUID.randomUUID(),
                    letter.eventId(),
                    letter.type(),
                    letter.handler(),
                    letter.topic(),
                    letter.partition(),
                    letter.offset(),
                    letter.messageKey(),
                    letter.reason(),
                    letter.correlationId(),
                    letter.payload(),
                    Timestamp.from(Instant.now()),
                    Timestamp.from(Instant.now()));
        }

        // Loud, and at error, because an event nobody can handle is a thing that has stopped
        // happening rather than a thing that is slow. Something downstream is now missing
        // information it was promised, and nobody finds that out from a debug line.
        log.error(
                "DEAD LETTER: {} {} could not be handled by {} and has been set aside: {}",
                letter.type(),
                letter.eventId(),
                letter.handler(),
                letter.reason());
    }

    /** What is set aside and not yet dealt with, worst first. */
    public List<DeadLetter> outstanding() {
        return jdbc.query(
                "select * from dead_letter where redelivered_at is null "
                        + "order by attempts desc, first_failed_at asc limit 200",
                DeadLetters::read);
    }

    public Optional<DeadLetter> find(UUID id) {
        return jdbc.query("select * from dead_letter where id = ?", DeadLetters::read, id).stream()
                .findFirst();
    }

    /**
     * Marks one as sent back for another try.
     *
     * <p>Not deleted. What went wrong and how often is the useful part, and a table that
     * forgets its failures the moment somebody retries them cannot answer whether a retry
     * helped.
     */
    public void markRedelivered(UUID id) {
        jdbc.update(
                "update dead_letter set redelivered_at = ? where id = ?",
                Timestamp.from(Instant.now()),
                id);
    }

    /** How many are outstanding, per handler, for anything that wants to notice. */
    public List<java.util.Map<String, Object>> summary() {
        return jdbc.queryForList(
                "select handler, type, count(*) as outstanding from dead_letter "
                        + "where redelivered_at is null group by handler, type order by 3 desc");
    }

    private static DeadLetter read(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        Timestamp redelivered = row.getTimestamp("redelivered_at");
        return new DeadLetter(
                row.getObject("id", UUID.class),
                row.getObject("event_id", UUID.class),
                row.getString("type"),
                row.getString("handler"),
                row.getString("topic"),
                row.getObject("partition", Integer.class),
                row.getObject("offset", Long.class),
                row.getString("message_key"),
                row.getString("reason"),
                row.getString("correlation_id"),
                row.getString("payload"),
                row.getInt("attempts"),
                row.getTimestamp("first_failed_at").toInstant(),
                redelivered == null ? null : redelivered.toInstant());
    }
}
