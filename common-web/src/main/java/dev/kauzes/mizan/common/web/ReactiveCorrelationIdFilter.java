package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The gateway is reactive, so the id is stamped onto the forwarded request rather than
 * held in a thread local that a downstream hop would not see.
 */
public class ReactiveCorrelationIdFilter implements WebFilter, Ordered {

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

        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.header(CorrelationContext.HEADER, correlationId))
                .build();

        // Set on commit rather than now: the proxied response carries the downstream copy of
        // this header, and setting it beforehand leaves the caller holding two of them.
        mutated.getResponse().beforeCommit(() -> {
            mutated.getResponse().getHeaders().set(CorrelationContext.HEADER, correlationId);
            return Mono.empty();
        });

        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
