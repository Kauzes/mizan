package dev.kauzes.mizan.common.web.logging;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.correlation.KafkaCorrelation;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Puts the correlation id back on the thread before a consumer handles a message.
 *
 * <p>A request arrives with a header and a filter puts the id in the logging context. A Kafka
 * record arrives on a consumer thread that has never seen a request, so without this every
 * line a consumer writes is unattributed — and those are exactly the lines somebody reads when
 * asking why a merchant was never told about a payment.
 *
 * <p>{@link KafkaCorrelation} has been able to read that header since MIZ-22 and nothing ever
 * called it. That is the fourth time on this platform that a correct, unreachable component
 * looked exactly like a working one, so this is wired once, here, and the services get it by
 * consuming.
 *
 * <p>The id is the producer's, not a new one: the point is that the line a consumer writes and
 * the line the payment service wrote are findable by the same search. An event with no id —
 * one produced before this platform carried them, or by something else entirely — gets a fresh
 * one rather than none, because an unattributed line is worse than a line attributed to work
 * nobody can trace further back.
 *
 * <p>The trace id is not handled here. Spring Kafka's own observation support puts that on the
 * thread from the {@code traceparent} header, which the outbox wrote (MIZ-78).
 */
public class EveryRecordIsSomebodysRequest implements RecordInterceptor<String, String> {

    @Override
    public ConsumerRecord<String, String> intercept(
            ConsumerRecord<String, String> record, Consumer<String, String> consumer) {

        CorrelationContext.set(KafkaCorrelation.from(record.headers())
                .orElseGet(CorrelationContext::generate));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> unused) {
        // Cleared rather than left for the next record to overwrite. A consumer thread is
        // reused, and an id that outlived its message would attribute the next failure — or a
        // rebalance, or a shutdown — to a payment that had nothing to do with it.
        CorrelationContext.clear();
    }
}
