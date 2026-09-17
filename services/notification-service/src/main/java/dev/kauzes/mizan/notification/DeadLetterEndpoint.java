package dev.kauzes.mizan.notification;

import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.common.web.inbox.DeadLetters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * What could not be handled, and the way to try it again.
 *
 * <p>An actuator endpoint rather than an API route, for the same reason the ledger's integrity
 * check is one: this is a question about the service, not about one merchant's data, and there
 * is no merchant who should be asking it. A dead lettered event is an operator's problem.
 *
 * <p>Reachable through the gateway's internal route, which needs a token, and not on the
 * public list.
 */
@Component
@Endpoint(id = "deadletters")
public class DeadLetterEndpoint {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterEndpoint.class);

    private final DeadLetters deadLetters;
    private final KafkaTemplate<String, String> kafka;

    public DeadLetterEndpoint(DeadLetters deadLetters, KafkaTemplate<String, String> kafka) {
        this.deadLetters = deadLetters;
        this.kafka = kafka;
    }

    @ReadOperation
    public Map<String, Object> outstanding() {
        List<DeadLetters.DeadLetter> letters = deadLetters.outstanding();
        return Map.of(
                "outstanding", letters.size(),
                "byHandler", deadLetters.summary(),
                "letters", letters);
    }

    /**
     * Sends one back to the topic it came from.
     *
     * <p>Republished rather than handed straight to the handler, so that it arrives exactly as
     * an ordinary delivery does and goes through the same inbox. A redelivery that took a
     * different path would be testing a path nothing else uses, on the one event already known
     * to be difficult.
     *
     * <p>Safe if the event was in fact handled before it was set aside: the inbox will find its
     * own record and do nothing. Safe if it was not: it is handled, once.
     */
    @WriteOperation
    public Map<String, Object> redeliver(@Selector String id) {
        UUID deadLetterId = UUID.fromString(id);

        return deadLetters
                .find(deadLetterId)
                .map(letter -> {
                    // Under the key it originally had, so it lands in the partition its
                    // payment's other events are in and stays in order relative to them.
                    kafka.send(letter.topic(), letter.messageKey(), letter.payload());
                    deadLetters.markRedelivered(deadLetterId);

                    log.warn(
                            "redelivered {} {} to {} after {} failure(s)",
                            letter.type(),
                            letter.eventId(),
                            letter.topic(),
                            letter.attempts());

                    return Map.<String, Object>of(
                            "redelivered", letter.eventId(),
                            "to", letter.topic(),
                            "afterFailures", letter.attempts());
                })
                .orElse(Map.of("error", "no dead letter with that id"));
    }

    /**
     * Records that nothing more will be done about one, and why.
     *
     * <p>For the event whose cause cannot be fixed — a payload poisoned by a test, an event
     * naming something that never existed, a handler that no longer exists. Redelivering it
     * only puts it back; before this, such a letter stayed outstanding forever and the alert
     * watching the count could never clear, which is how an alert becomes one people ignore.
     *
     * <p>Not a delete. The row, its reason and its payload stay readable: it is the only record
     * that a merchant was never told something. And a name and a reason are required, because a
     * decision nobody can account for later is not a decision, it is a disappearance.
     *
     * <p>If the same event fails again afterwards, it comes back. Closing speaks about what was
     * set aside, not about the future.
     */
    @WriteOperation
    public Map<String, Object> close(
            @Selector String id,
            @Selector String verb,
            @Nullable String closedBy,
            @Nullable String why) {

        if (!"close".equals(verb)) {
            throw new UnprocessableException(
                    "A dead letter can be redelivered or closed: " + verb + " is neither.");
        }
        if (closedBy == null || closedBy.isBlank() || why == null || why.isBlank()) {
            throw new UnprocessableException(
                    "Closing a dead letter needs closedBy and why: it is the record that nobody "
                            + "will do anything more about an event a merchant was never told.");
        }

        UUID deadLetterId = UUID.fromString(id);
        return deadLetters
                .find(deadLetterId)
                .map(letter -> {
                    deadLetters.close(deadLetterId, closedBy.trim(), why.trim());
                    return Map.<String, Object>of(
                            "closed", letter.eventId(),
                            "closedBy", closedBy.trim(),
                            "why", why.trim(),
                            "afterFailures", letter.attempts(),
                            "kept", "the letter, its reason and its payload stay readable");
                })
                .orElse(Map.of("error", "no dead letter with that id"));
    }
}
