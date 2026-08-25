package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts a correlation id on every inbound request and echoes it back on the response. */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId =
                CorrelationContext.sanitiseOrGenerate(request.getHeader(CorrelationContext.HEADER));
        CorrelationContext.set(correlationId);
        response.setHeader(CorrelationContext.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
