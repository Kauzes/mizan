package dev.kauzes.mizan.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KafkaCorrelationTest {

    @AfterEach
    void clear() {
        CorrelationContext.clear();
    }

    @Test
    void survivesARoundTripThroughHeaders() {
        Headers headers = new RecordHeaders();
        KafkaCorrelation.apply(headers, "trace-42");

        assertThat(KafkaCorrelation.from(headers)).contains("trace-42");
    }

    @Test
    void carriesWhateverTheCurrentThreadIsWorkingUnder() {
        CorrelationContext.set("from-the-request");
        Headers headers = new RecordHeaders();

        KafkaCorrelation.applyCurrent(headers);

        assertThat(KafkaCorrelation.from(headers)).contains("from-the-request");
    }

    @Test
    void generatesOneWhenTheProducerHasNoContext() {
        Headers headers = new RecordHeaders();

        KafkaCorrelation.applyCurrent(headers);

        assertThat(KafkaCorrelation.from(headers)).isPresent().get().asString().isNotBlank();
    }

    @Test
    void isEmptyWhenTheMessageCarriesNoId() {
        assertThat(KafkaCorrelation.from(new RecordHeaders())).isEmpty();
    }

    @Test
    void doesNotTrustAnIdArrivingOnAMessage() {
        Headers headers = new RecordHeaders();
        headers.add(KafkaCorrelation.HEADER, "forged\nline".getBytes(StandardCharsets.UTF_8));

        assertThat(KafkaCorrelation.from(headers)).isPresent().get().asString()
                .doesNotContain("forged");
    }

    @Test
    void replacingTheIdDoesNotLeaveTheOldOneBehind() {
        Headers headers = new RecordHeaders();
        KafkaCorrelation.apply(headers, "first");
        KafkaCorrelation.apply(headers, "second");

        assertThat(headers.headers(KafkaCorrelation.HEADER)).hasSize(1);
        assertThat(KafkaCorrelation.from(headers)).contains("second");
    }
}
