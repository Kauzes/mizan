package dev.kauzes.mizan.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

class ReactiveCorrelationIdFilterTest {

    private final ReactiveCorrelationIdFilter filter = new ReactiveCorrelationIdFilter();

    @Test
    void stampsTheIdOntoTheForwardedRequest() {
        MockServerWebExchange exchange = exchangeWith("trace-1");
        String[] forwarded = new String[1];

        filter.filter(exchange, forwardingChain(forwarded, false)).block();

        assertThat(forwarded[0]).isEqualTo("trace-1");
    }

    @Test
    void mintsAnIdWhenTheCallerSendsNone() {
        MockServerWebExchange exchange = exchangeWith(null);
        String[] forwarded = new String[1];

        filter.filter(exchange, forwardingChain(forwarded, false)).block();

        assertThat(forwarded[0]).matches("[0-9a-f-]{36}");
    }

    @Test
    void refusesAnIdThatCouldForgeALogLine() {
        MockServerWebExchange exchange = exchangeWith("evil id;drop");
        String[] forwarded = new String[1];

        filter.filter(exchange, forwardingChain(forwarded, false)).block();

        assertThat(forwarded[0]).isNotEqualTo("evil id;drop").matches("[0-9a-f-]{36}");
    }

    @Test
    void returnsExactlyOneHeaderWhenTheDownstreamEchoesItToo() {
        MockServerWebExchange exchange = exchangeWith("trace-1");
        String[] forwarded = new String[1];

        filter.filter(exchange, forwardingChain(forwarded, true)).block();

        assertThat(exchange.getResponse().getHeaders().get(CorrelationContext.HEADER))
                .containsExactly("trace-1");
    }

    private static MockServerWebExchange exchangeWith(String correlationId) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/v1/payments");
        if (correlationId != null) {
            request = request.header(CorrelationContext.HEADER, correlationId);
        }
        return MockServerWebExchange.from(request.build());
    }

    /** Stands in for the proxy, optionally echoing the header back the way a real one does. */
    private static WebFilterChain forwardingChain(String[] captured, boolean echoHeader) {
        return (ServerWebExchange forwardedExchange) -> {
            captured[0] = forwardedExchange.getRequest()
                    .getHeaders()
                    .getFirst(CorrelationContext.HEADER);
            if (echoHeader) {
                forwardedExchange.getResponse()
                        .getHeaders()
                        .add(CorrelationContext.HEADER, captured[0]);
            }
            return forwardedExchange.getResponse().setComplete();
        };
    }
}
