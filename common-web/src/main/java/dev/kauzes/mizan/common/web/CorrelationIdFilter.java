package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.web.trace.Traces;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id on every inbound request, echoes it back, and ties it to the trace.
 *
 * <p>Two ids, and neither replaces the other. The correlation id is short, is chosen by this
 * platform, survives being read out over a telephone and appears on every log line. The trace
 * id is thirty-two hexadecimal characters and is the thing a tracing system can look up. A
 * problem report names one of them, and whoever picks it up needs the other.
 *
 * <p>So both directions are made to work. The correlation id goes onto the span as an
 * attribute, which makes a trace findable from an id somebody read out; the trace id goes onto
 * the response, which makes a trace findable from a request somebody made. A span attribute
 * rather than an observation key value on purpose — a key value becomes a metric label too,
 * and a correlation id as a metric label is the unbounded dimension ADR 0041 exists to keep
 * out of the monitoring system.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    /** What the span is tagged with, so a trace can be found from an id read out loud. */
    public static final String CORRELATION_ATTRIBUTE = "mizan.correlation_id";

    /** What the response carries, so a request can be followed without asking anybody. */
    public static final String TRACE_HEADER = "X-Trace-Id";

    private final Traces traces;

    public CorrelationIdFilter(Traces traces) {
        this.traces = traces;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId =
                CorrelationContext.sanitiseOrGenerate(request.getHeader(CorrelationContext.HEADER));
        CorrelationContext.set(correlationId);
        response.setHeader(CorrelationContext.HEADER, correlationId);

        // The span already exists: Boot's observation filter runs ahead of this one, which is
        // what the order below is for. A trace started after this point would not include the
        // request it belongs to.
        traces.note(CORRELATION_ATTRIBUTE, correlationId);
        String traceId = traces.id();
        if (traceId != null) {
            response.setHeader(TRACE_HEADER, traceId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }

    @Override
    public int getOrder() {
        // After Boot's server observation filter, which sits at HIGHEST_PRECEDENCE + 1 and is
        // what starts the span this one annotates.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
