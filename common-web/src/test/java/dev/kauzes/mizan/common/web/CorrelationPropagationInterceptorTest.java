package dev.kauzes.mizan.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

class CorrelationPropagationInterceptorTest {

    private final CorrelationPropagationInterceptor interceptor =
            new CorrelationPropagationInterceptor();

    @AfterEach
    void clear() {
        CorrelationContext.clear();
    }

    @Test
    void putsTheCurrentIdOnAnOutboundCall() throws IOException {
        CorrelationContext.set("inbound-99");
        MockClientHttpRequest request = request();

        interceptor.intercept(request, new byte[0], ok());

        assertThat(request.getHeaders().getFirst(CorrelationContext.HEADER))
                .isEqualTo("inbound-99");
    }

    @Test
    void addsNothingWhenThereIsNoIdToPropagate() throws IOException {
        MockClientHttpRequest request = request();

        interceptor.intercept(request, new byte[0], ok());

        assertThat(request.getHeaders().containsHeader(CorrelationContext.HEADER)).isFalse();
    }

    @Test
    void leavesAnIdTheCallerSetDeliberately() throws IOException {
        CorrelationContext.set("inbound-99");
        MockClientHttpRequest request = request();
        request.getHeaders().set(CorrelationContext.HEADER, "explicit-override");

        interceptor.intercept(request, new byte[0], ok());

        assertThat(request.getHeaders().getFirst(CorrelationContext.HEADER))
                .isEqualTo("explicit-override");
    }

    private static MockClientHttpRequest request() {
        return new MockClientHttpRequest(HttpMethod.POST, URI.create("http://ledger-service/entries"));
    }

    private static ClientHttpRequestExecution ok() {
        return (request, body) -> response();
    }

    private static ClientHttpResponse response() {
        return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
    }
}
