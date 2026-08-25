package dev.kauzes.mizan.common.correlation;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

/** Carries the correlation id across a Kafka hop, where there is no request to attach it to. */
public final class KafkaCorrelation {

    public static final String HEADER = "mizan-correlation-id";

    private KafkaCorrelation() {
    }

    public static void apply(Headers headers, String correlationId) {
        headers.remove(HEADER);
        headers.add(HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
    }

    /** Applies whatever id the current thread is working under, generating one if there is none. */
    public static void applyCurrent(Headers headers) {
        apply(headers, CorrelationContext.current().orElseGet(CorrelationContext::generate));
    }

    public static Optional<String> from(Headers headers) {
        Header header = headers.lastHeader(HEADER);
        if (header == null || header.value() == null) {
            return Optional.empty();
        }
        return Optional.of(CorrelationContext.sanitiseOrGenerate(
                new String(header.value(), StandardCharsets.UTF_8)));
    }
}
