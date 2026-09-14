package dev.kauzes.mizan.common.web.trace;

/**
 * The trace in progress on this thread, for the three things this platform needs from one.
 *
 * <p>An interface rather than a direct use of a tracer, so that nothing in the outbox or the
 * request filters has a compile time dependency on a tracing library. A service built without
 * tracing gets {@link #NONE} and behaves exactly as it did before, which matters because the
 * outbox is the most careful code here and a debugging convenience is a poor reason to give it
 * a new way to fail to load.
 */
public interface Traces {

    /** A service with no tracing configured. Never null, always nothing, never throws. */
    Traces NONE = new Traces() {

        @Override
        public String parent() {
            return null;
        }

        @Override
        public String id() {
            return null;
        }

        @Override
        public void note(String name, String value) {
            // Nothing to note it on.
        }
    };

    /**
     * The current trace as a W3C {@code traceparent}: {@code 00-<trace id>-<span id>-<flags>},
     * or null when there is no trace. One field rather than the propagator's whole carrier,
     * because one column is what the outbox has and {@code tracestate} carries vendor routing
     * this platform does not use.
     */
    String parent();

    /**
     * The trace id alone, which is the part a person hands to somebody else, or null.
     *
     * <p>Not a replacement for the correlation id and not replaced by it. The correlation id
     * is short, is chosen by this platform, and can be read out over a telephone; this is 32
     * hexadecimal characters and is what a tracing system can look up.
     */
    String id();

    /**
     * Records something about this span, and only about this span.
     *
     * <p>Deliberately not an observation key value, which would become a metric label as well
     * and put an unbounded dimension into the monitoring system. A span attribute is per
     * trace, which is exactly where a correlation id or a payment id belongs. ADR 0041 is the
     * other half of that rule.
     */
    void note(String name, String value);
}
