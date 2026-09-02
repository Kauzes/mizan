package dev.kauzes.mizan.ledger.internal;

import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.identity.ServiceCredential;
import dev.kauzes.mizan.common.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Guards everything under {@code /internal/}, whatever it turns out to be.
 *
 * <p>Written as a filter over a prefix rather than as a check inside one controller, because
 * the endpoints here are the ones that can do what a merchant may not, and the failure mode of
 * a per-endpoint check is somebody adding the next endpoint and not adding the check. The
 * failure mode of this is a 401 on something that should have been open, which is the
 * direction worth failing in.
 */
@Configuration
public class ServiceCallsOnly {

    private static final Logger log = LoggerFactory.getLogger(ServiceCallsOnly.class);

    @Bean
    FilterRegistrationBean<InternalCallFilter> internalCallsNeedAServiceCredential(
            @Value("${mizan.internal.service-token:}") String token, ObjectMapper json) {

        if (token.isBlank()) {
            // Refused at startup rather than at the first call. A blank secret would make
            // every internal endpoint open, and nothing about a running service would say so.
            throw new IllegalStateException(
                    "mizan.internal.service-token is not set. The internal endpoints move money "
                            + "between the platform's books and a merchant's, and they are not "
                            + "run without a credential.");
        }

        FilterRegistrationBean<InternalCallFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new InternalCallFilter(token, json));
        registration.addUrlPatterns("/internal/*");
        // Before anything that might act on the request, and after correlation, which only
        // reads a header.
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    static class InternalCallFilter extends OncePerRequestFilter {

        private final String token;
        private final ObjectMapper json;

        InternalCallFilter(String token, ObjectMapper json) {
            this.token = token;
            this.json = json;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {

            if (ServiceCredential.matches(
                    request.getHeader(ServiceCredential.HEADER), token)) {
                chain.doFilter(request, response);
                return;
            }

            log.warn(
                    "refused an internal call to {} that carried no service credential",
                    request.getRequestURI());

            ProblemDetail problem = Problems.of(
                    ErrorCode.UNAUTHORIZED,
                    "This endpoint is not part of the public API.",
                    java.util.List.of());
            response.setStatus(problem.getStatus());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(json.writeValueAsString(problem));
        }
    }
}
