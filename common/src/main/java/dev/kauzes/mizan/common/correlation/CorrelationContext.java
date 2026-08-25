package dev.kauzes.mizan.common.correlation;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * The id that ties one request together across services, held in the logging context so
 * every line carries it without being passed around by hand.
 */
public final class CorrelationContext {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final int MAX_LENGTH = 64;

    private CorrelationContext() {
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    public static String currentOrEmpty() {
        return current().orElse("");
    }

    public static void set(String correlationId) {
        MDC.put(MDC_KEY, correlationId);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * Accepts an inbound id only if it is short and alphanumeric, because it reaches the
     * logs and an attacker controlled header should not be able to forge log lines.
     */
    public static String sanitiseOrGenerate(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_LENGTH) {
            return generate();
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!allowed) {
                return generate();
            }
        }
        return candidate;
    }
}
