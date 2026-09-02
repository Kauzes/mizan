package dev.kauzes.mizan.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes a request repeatable and its response recordable.
 *
 * <p>Two jobs, both mechanical. The body is buffered so that {@link IdempotencyInterceptor}
 * can tell whether a repeat is the same request, and the response is captured so that what
 * the handler produced can be stored and handed back to a repeat later.
 *
 * <p>Only writes under {@code /api/}, because nothing else can be repeated harmfully and
 * wrapping every request would mean buffering every response the platform serves.
 */
public class IdempotencyFilter extends OncePerRequestFilter implements Ordered {

    static final String BUFFERED_BODY = IdempotencyFilter.class.getName() + ".body";

    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String GUARDED_PREFIX = "/api/";

    private final int maximumBody;

    public IdempotencyFilter(int maximumBody) {
        this.maximumBody = maximumBody;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!isAWriteToOurApi(request)) {
            chain.doFilter(request, response);
            return;
        }

        BufferedBody buffered = BufferedBody.of(request, maximumBody);
        buffered.setAttribute(BUFFERED_BODY, buffered.body());

        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(buffered, captured);
        } finally {
            // The wrapper holds the body back until this is called, so it has to happen
            // however the request ended.
            captured.copyBodyToResponse();
        }
    }

    private static boolean isAWriteToOurApi(HttpServletRequest request) {
        return WRITES.contains(request.getMethod())
                && request.getRequestURI().startsWith(GUARDED_PREFIX);
    }

    @Override
    public int getOrder() {
        // After the correlation id, so a refusal from here carries one.
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }
}
