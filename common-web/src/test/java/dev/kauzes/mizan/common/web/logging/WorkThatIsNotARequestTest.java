package dev.kauzes.mizan.common.web.logging;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.correlation.KafkaCorrelation;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The two places a log line has no request to inherit an id from.
 *
 * <p>A consumer thread has never seen a request and a scheduler thread never will, so without
 * these every line either writes is unattributed — and those are the lines somebody reads when
 * asking why a merchant was never told about a payment, or why a batch never closed.
 *
 * <p>Each assertion is about what is true <em>while the work runs</em> and about what is left
 * behind afterwards. The second half matters as much: both kinds of thread are reused, so an
 * id that outlived its work would attribute the next failure to the wrong payment, which is
 * worse than no id at all.
 */
class WorkThatIsNotARequestTest {

    private final EveryRecordIsSomebodysRequest records = new EveryRecordIsSomebodysRequest();
    private final EverySweepIsItsOwnPieceOfWork sweeps = new EverySweepIsItsOwnPieceOfWork();

    @AfterEach
    void clear() {
        CorrelationContext.clear();
    }

    @Test
    void aConsumerWorksUnderTheIdTheProducerSent() {
        RecordHeaders headers = new RecordHeaders();
        KafkaCorrelation.apply(headers, "from-the-payment-service");

        records.intercept(record(headers), null);

        // The producer's, not a new one. The point is that the line the consumer writes and
        // the line the payment service wrote are found by the same search.
        assertThat(CorrelationContext.current()).contains("from-the-payment-service");
    }

    @Test
    void andAMessageWithNoIdGetsOneRatherThanNone() {
        records.intercept(record(new RecordHeaders()), null);

        // An event from before this platform carried ids, or from something else entirely.
        // Unattributed is worse than attributed to work nobody can trace further back.
        assertThat(CorrelationContext.current()).isPresent().get().asString().isNotBlank();
    }

    @Test
    void andNothingIsLeftOnTheThreadForTheNextMessage() {
        RecordHeaders headers = new RecordHeaders();
        KafkaCorrelation.apply(headers, "one-message");
        ConsumerRecord<String, String> record = record(headers);

        records.intercept(record, null);
        records.afterRecord(record, null);

        assertThat(CorrelationContext.current())
                .as("a consumer thread is reused, and this one is finished with")
                .isEmpty();
    }

    @Test
    void everyRunOfASweepIsItsOwnPieceOfWork() {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        sweeps.decorate(() -> first.set(CorrelationContext.currentOrEmpty())).run();
        sweeps.decorate(() -> second.set(CorrelationContext.currentOrEmpty())).run();

        assertThat(first.get()).isNotBlank();
        assertThat(second.get()).isNotBlank();
        // Per execution, not per thread and not per service. What somebody wants when a run
        // goes wrong is every line that run wrote and nothing from the run before it.
        assertThat(first.get()).isNotEqualTo(second.get());
    }

    @Test
    void andASweepThatThrowsStillTidiesUpAfterItself() {
        Runnable failing = sweeps.decorate(() -> {
            throw new IllegalStateException("the batch could not be closed");
        });

        assertThat(org.assertj.core.api.Assertions.catchThrowable(failing::run)).isNotNull();
        assertThat(CorrelationContext.current())
                .as("the failing run is over, and the scheduler thread is about to be reused")
                .isEmpty();
    }

    private static ConsumerRecord<String, String> record(RecordHeaders headers) {
        return new ConsumerRecord<>(
                "mizan.payment.events",
                0,
                7L,
                ConsumerRecord.NO_TIMESTAMP,
                org.apache.kafka.common.record.TimestampType.NO_TIMESTAMP_TYPE,
                ConsumerRecord.NULL_SIZE,
                ConsumerRecord.NULL_SIZE,
                "a-key",
                "{}",
                headers,
                java.util.Optional.empty());
    }
}
