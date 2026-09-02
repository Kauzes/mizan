package dev.kauzes.mizan.notification;

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
}
