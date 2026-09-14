package dev.kauzes.mizan.common.correlation;

import java.util.Optional;
import org.slf4j.MDC;

/**
 * The trace id of whatever is happening on this thread, read the same way the correlation id
 * is.
 *
 * <p>Tracing puts the id into the logging context so that every log line can carry it; this
 * reads it back out. That makes it available in the two kinds of place where a bean cannot be
 * injected and a parameter cannot reasonably be threaded through — a JPA entity recording what
 * just happened to it, and anything constructed by a framework rather than by this platform.
 * It is the same argument that put the correlation id here: ambient context is exactly what a
 * logging context is for, and the alternative is a trace id parameter on every state change
 * method of every aggregate, forgotten on the one that eventually matters.
 *
 * <p>Empty when nothing is tracing, which is every unit test and any service built without it.
 * A caller stores null and nothing else changes.
 *
 * <p>What this cannot answer is whether the trace will be kept, which is why the outbox asks
 * the tracer directly instead of reading this. See {@code Traces} in {@code common-web}.
 */
public final class CurrentTrace {

    /** Where tracing leaves it. The same key the logging pattern reads. */
    public static final String MDC_KEY = "traceId";

    private CurrentTrace() {
    }

    public static Optional<String> id() {
        String traceId = MDC.get(MDC_KEY);
        return traceId == null || traceId.isBlank() ? Optional.empty() : Optional.of(traceId);
    }

    /** The id, or null, for a caller storing it in a column that allows one. */
    public static String idOrNull() {
        return id().orElse(null);
    }
}
