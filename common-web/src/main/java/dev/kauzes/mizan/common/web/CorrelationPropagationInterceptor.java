package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Carries the id onto outbound calls, so one request keeps one id across every service. */
public class CorrelationPropagationInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {

        if (!request.getHeaders().containsHeader(CorrelationContext.HEADER)) {
            CorrelationContext.current()
                    .ifPresent(id -> request.getHeaders().set(CorrelationContext.HEADER, id));
        }
        return execution.execute(request, body);
    }
}
