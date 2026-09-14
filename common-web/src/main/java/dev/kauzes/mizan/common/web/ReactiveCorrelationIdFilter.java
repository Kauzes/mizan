package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.web.trace.Traces;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The gateway is reactive, so the id is stamped onto the forwarded request rather than
 * held in a thread local that a downstream hop would not see.
 *
 * <p>The trace id goes back on the response the same way the servlet filter does it, and it
 * matters more here: this is the service a person's request reaches first, so the trace that
 * starts at the edge is the one that contains every hop the platform made because of it.
 */
public class ReactiveCorrelationIdFilter implements WebFilter, Ordered {

    private final Traces traces;

    public ReactiveCorrelationIdFilter(Traces traces) {
        this.traces = traces;
    }

    /**
     * Where the id is left for anything that has the exchange but not the mutated request.
     *
     * <p>An error handler runs outside the filter chain and is handed the original exchange,
     * so the header this filter added is not on the request it sees. Attributes are shared
     * between an exchange and its mutations, which makes this the one place both can read.
     */
    public static final String ATTRIBUTE = CorrelationContext.class.getName() + ".id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationContext.sanitiseOrGenerate(
                exchange.getRequest().getHeaders().getFirst(CorrelationContext.HEADER));

        exchange.getAttributes().put(ATTRIBUTE, correlationId);

        // The edge is where a person's request arrives, so it is where the trace worth
        // handing over begins. Both ids are connected here for the same reason as in the
        // servlet filter: a problem report names one of them and whoever picks it up needs
        // the other.
        traces.note(CorrelationIdFilter.CORRELATION_ATTRIBUTE, correlationId);
        String traceId = traces.id();

        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.header(CorrelationContext.HEADER, correlationId))
                .build();

        // Set on commit rather than now: the proxied response carries the downstream copy of
        // this header, and setting it beforehand leaves the caller holding two of them.
        mutated.getResponse().beforeCommit(() -> {
            mutated.getResponse().getHeaders().set(CorrelationContext.HEADER, correlationId);
            if (traceId != null) {
                mutated.getResponse()
                        .getHeaders()
                        .set(CorrelationIdFilter.TRACE_HEADER, traceId);
            }
            return Mono.empty();
        });

        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
