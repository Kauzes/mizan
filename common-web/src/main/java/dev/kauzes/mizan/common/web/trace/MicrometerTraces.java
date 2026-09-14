package dev.kauzes.mizan.common.web.trace;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/**
 * What the platform's tracer says is happening on this thread.
 *
 * <p>The traceparent is formatted here rather than through the propagator, because a
 * propagator writes into a carrier of several fields and the outbox has one column. The format
 * is fixed by the W3C trace context specification and has exactly one version so far; if a
 * second ever appears, this is the method that has to know about it.
 */
public class MicrometerTraces implements Traces {

    private static final String VERSION = "00";
    private static final String SAMPLED = "01";
    private static final String NOT_SAMPLED = "00";

    private final Tracer tracer;

    public MicrometerTraces(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public String parent() {
        TraceContext context = context();
        if (context == null) {
            return null;
        }
        return VERSION + "-" + context.traceId() + "-" + context.spanId() + "-"
                + (Boolean.TRUE.equals(context.sampled()) ? SAMPLED : NOT_SAMPLED);
    }

    @Override
    public String id() {
        TraceContext context = context();
        return context == null ? null : context.traceId();
    }

    @Override
    public void note(String name, String value) {
        Span span = tracer.currentSpan();
        if (span != null && value != null && !value.isBlank()) {
            span.tag(name, value);
        }
    }

    /**
     * Null on a thread with no trace — work on a scheduler rather than in a request. Inventing
     * a trace id there would produce a trace of one span, unrelated to anything a person is
     * looking for, which is worse than admitting there is nothing to carry.
     */
    private TraceContext context() {
        Span span = tracer.currentSpan();
        return span == null ? null : span.context();
    }
}
