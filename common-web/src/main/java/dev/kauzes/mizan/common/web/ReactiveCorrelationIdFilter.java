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

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationContext.sanitiseOrGenerate(
                exchange.getRequest().getHeaders().getFirst(CorrelationContext.HEADER));

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
