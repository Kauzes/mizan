package dev.kauzes.mizan.common.web.inbox;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles an event once, however many times it arrives.
 *
 * <p>MIZ-48 delivers at least once and says so: the relay publishes and then marks the row, so
 * anything that dies in between publishes again. This is the other side of that admission.
 * Without it, a redelivered {@code payment.captured} is a second receipt sent to a customer,
 * or a number written into the books twice.
 *
 * <h2>Why this is not the idempotency records from MIZ-41</h2>
 *
 * <p>They look alike and their transactional requirements are opposite, which is the whole
 * reason they are separate.
 *
 * <p>An API idempotency record is written <em>before</em> the handler runs and committed on its
 * own, so that a second request arriving at the same moment can see the claim and wait for the
 * first one's answer. It also has to keep that answer — status and body — because an HTTP
 * caller is waiting to be given the same response again.
 *
 * <p>This is written <em>with</em> the handling, in one transaction. A handler that did its
 * work and then failed to record it would be back where the outbox started, and there is
 * nobody waiting for a response, so there is nothing to store and replay. Sharing an
 * implementation would mean one of the two being wrong about when it commits.
 *
 * <p>There is no request fingerprint either. A caller can honestly reuse an idempotency key by
 * mistake; a producer cannot reuse an event id, because the id is generated once for an event
 * that is then immutable.
 */
public class Inbox {

    private static final Logger log = LoggerFactory.getLogger(Inbox.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public Inbox(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        // Managed here rather than by an annotation, because the boundary has to enclose both
        // the claim and the work, and the work is a lambda somebody else wrote. An annotation
        // on a method that takes a callback cannot put the boundary in the right place.
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * Runs the work for this message, unless this handler has already run it.
     *
     * <p>The handler is named because two handlers in one service may both care about the same
     * event, and each has to see it. "Already handled" is a question about a handler, not
     * about a service.
     *
     * @param handler which consumer this is, as a stable name
     * @param message the raw message from the topic
     * @param work what to do with it, run inside the transaction that records it
     */
    public void once(String handler, String message, Consumer<ReceivedEvent> work) {
        ReceivedEvent event;
        try {
            event = ReceivedEvent.from(json.readTree(message));
        } catch (RuntimeException notAnEvent) {
            // A message that cannot be read will not read differently in a second. Saying so
            // is what stops it being retried in a loop while everything behind it waits: it
            // goes straight to the dead letter topic, where a person can look at it.
            throw new UnprocessableEventException(
                    "this message is not an event this service can read: "
                            + notAnEvent.getMessage(),
                    notAnEvent);
        }

        // The id the producer put on the envelope, so a log line here can be joined to the
        // request that caused the event several services ago.
        String was = CorrelationContext.currentOrEmpty();
        CorrelationContext.set(
                CorrelationContext.sanitiseOrGenerate(event.correlationId()));

        try {
            transaction.executeWithoutResult(status -> {
                if (!firstTime(handler, event)) {
                    // Not an error, and not worth a warning: at least once means this is the
                    // system working, not failing.
                    log.debug("{} has already handled {}", handler, event.eventId());
                    return;
                }
                work.accept(event);
                log.info("{} handled {} {}", handler, event.type(), event.eventId());
            });
        } finally {
            if (was.isEmpty()) {
                CorrelationContext.clear();
            } else {
                CorrelationContext.set(was);
            }
        }
    }

    /**
     * Claims this event for this handler.
     *
     * <p>By inserting rather than by asking first. A check would answer for the moment before
     * the insert, and two deliveries racing would both be told they were the first. The unique
     * constraint is what actually decides, and it decides once.
     *
     * <p>{@code on conflict do nothing} rather than catching a duplicate key. The insert has
     * to happen in the same transaction as the work, and a constraint violation inside a
     * Postgres transaction leaves that transaction unusable — so catching the exception here
     * would mean the commit afterwards failing anyway. This asks the database not to raise in
     * the first place, and reads the answer from how many rows it wrote.
     *
     * <p>A duplicate that another transaction has inserted but not committed blocks here until
     * that transaction ends, which is the behaviour worth having: two deliveries of one event
     * take turns, and the second finds the first's row and does nothing.
     *
     * @return whether this delivery is the one that gets to do the work
     */
    private boolean firstTime(String handler, ReceivedEvent event) {
        int claimed = jdbc.update(
                "insert into handled_event (handler, event_id, type, handled_at) "
                        + "values (?, ?, ?, ?) on conflict do nothing",
                handler,
                event.eventId(),
                event.type(),
                Timestamp.from(Instant.now()));

        return claimed == 1;
    }
}
