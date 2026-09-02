package dev.kauzes.mizan.common.web.outbox;

/**
 * Whatever actually sends an event somewhere.
 *
 * <p>An interface so that {@link OutboxRelay} — which owns the interesting part, the claiming
 * and the ordering and the backing off — knows nothing about Kafka. That keeps the relay
 * testable against a publisher that fails on purpose, which is the only way to check the
 * behaviour that matters: what happens to the events behind one that will not send.
 */
public interface EventPublisher {

    /**
     * Sends the event, and does not return until the other side has it.
     *
     * <p>Synchronous on purpose. The relay marks a row published once this returns, so a
     * method that returned as soon as the event was queued would have the relay recording as
     * delivered things that were still in a buffer in this process, and losing them on a
     * restart with no trace.
     *
     * @throws RuntimeException if the event was not accepted, in which case it will be tried
     *     again later
     */
    void publish(PendingEvent event);
}
