package dev.kauzes.mizan.common.web.inbox;

import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens to a message a handler will not accept.
 *
 * <p>Two things must both be true and pull in opposite directions. An event that fails because
 * something was briefly unavailable has to be tried again, or the platform loses information it
 * promised to deliver. An event that will fail identically forever must be got out of the way,
 * or it blocks its partition and takes every well formed event behind it down with it.
 *
 * <p>Retrying forever and dropping are the two defaults systems arrive at by accident, and
 * both are wrong. Bounded retry followed by setting the event aside is the arrangement that is
 * neither.
 */
public final class EventErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(EventErrorHandling.class);

    /** The suffix Spring's recoverer uses by default, kept so the convention is one thing. */
    public static final String DEAD_LETTER_SUFFIX = ".dead-letter";

    private EventErrorHandling() {
    }

    public static String deadLetterTopicFor(String topic) {
        return topic + DEAD_LETTER_SUFFIX;
    }

    /**
     * Retries a few times, then sends the message to the dead letter topic and moves on.
     *
     * <p>The delay grows, because a dependency that was unavailable a moment ago is likely to
     * still be, and retrying four times in a hundred milliseconds is one attempt with extra
     * steps.
     *
     * <p>The retries happen in the listener, which means the partition waits during them. That
     * is deliberate and is the price of ordering: the events behind this one are about other
     * payments, and letting them past would be fine, but letting the *same* payment's later
     * events past would not, and the consumer cannot tell them apart cheaply. The bound is what
     * keeps the wait short.
     */
    public static DefaultErrorHandler retryThenSetAside(
            KafkaOperations<?, ?> kafka, int retries, Duration firstDelay, Duration longestDelay) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafka,
                (record, exception) -> new TopicPartition(
                        deadLetterTopicFor(record.topic()),
                        // Let the broker choose. The dead letter topic's partitioning has
                        // nothing to do with the original's, and asking for partition 3 of a
                        // topic that has one is how a recoverer starts failing too.
                        -1));

        ExponentialBackOff backoff = new ExponentialBackOff();
        backoff.setInitialInterval(firstDelay.toMillis());
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(longestDelay.toMillis());
        // Attempts, not retries: the first delivery is one of them, so asking for three
        // retries means four goes at it in total.
        backoff.setMaxAttempts(retries + 1L);
        // So that a hundred consumers knocked over by the same outage do not all come back
        // at the same instant.
        backoff.setJitter(firstDelay.toMillis() / 4);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backoff);

        // A handler that says it will never manage this one is believed the first time. The
        // whole point of that exception is that trying again is a busy loop.
        handler.addNotRetryableExceptions(UnprocessableEventException.class);

        handler.setRetryListeners((record, exception, delivery) -> log.warn(
                "attempt {} to handle offset {} of {} failed: {}",
                delivery,
                record.offset(),
                record.topic(),
                exception.getMessage()));

        return handler;
    }
}
