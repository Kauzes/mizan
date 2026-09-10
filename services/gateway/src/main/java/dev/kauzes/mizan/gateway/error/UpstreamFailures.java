package dev.kauzes.mizan.gateway.error;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.web.Problems;
import dev.kauzes.mizan.common.web.ReactiveCorrelationIdFilter;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * What a caller is told when the service behind a route cannot answer.
 *
 * <p>Without this the gateway falls back to Spring's own error body — a JSON object with
 * {@code error}, {@code path} and {@code requestId} and no {@code code} at all. Every other
 * error this platform produces is an RFC 9457 problem detail carrying a code from a closed
 * set, and the documentation tells callers to branch on that code rather than on the status.
 * So the fallback broke the contract at the one moment a caller most needs it: telling "this
 * failed, try again" apart from "this was refused, do not".
 *
 * <p>Registered ahead of Boot's own handler rather than replacing it, so anything this does
 * not recognise still reaches the default and is still answered.
 */
@Component
@Order(-2)
public class UpstreamFailures implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UpstreamFailures.class);

    /**
     * What a caller is told, in both cases, and deliberately the same two sentences whatever
     * went wrong underneath.
     *
     * <p>Nothing about the target host, the exception or the stack reaches the caller. A
     * merchant cannot act on "connection refused: payment-service/172.19.0.7:8083", and
     * somebody probing the platform's shape should not be handed its topology by an error
     * message. What they can act on is whether to try again, which is what the code says.
     */
    private static final String UNAVAILABLE =
            "This platform could not reach the service that answers this request. "
                    + "Nothing was changed, and the request can be sent again.";

    private static final String TIMED_OUT =
            "The service that answers this request did not respond in time. Whether it acted "
                    + "is unknown, so send the request again with the same idempotency key.";

    private final ObjectMapper json;

    public UpstreamFailures(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        ErrorCode code = codeFor(failure);
        if (code == null || exchange.getResponse().isCommitted()) {
            // Not ours, or too late to say anything. Boot's handler is still behind us.
            return Mono.error(failure);
        }

        // The whole cause, at this end only. An operator needs the host and the stack; the
        // caller needs neither, and this is the line that keeps those two facts apart.
        log.warn(
                "{} {} could not be served: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                failure.toString(),
                failure);

        return write(exchange, code);
    }

    /**
     * Which of the two this is, or null for anything that is not an upstream failing.
     *
     * <p>Unwrapped, because what reaches here is usually a reactive wrapper around the thing
     * that actually happened, and matched on types rather than on message text.
     */
    private static ErrorCode codeFor(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MizanException mizan) {
                // Something downstream in the gateway already decided. It knows better.
                return mizan.errorCode();
            }
            ErrorCode alreadyDecided = gatewaySaid(cause);
            if (alreadyDecided != null) {
                return alreadyDecided;
            }
            if (cause instanceof TimeoutException || isReadTimeout(cause)) {
                return ErrorCode.UPSTREAM_TIMEOUT;
            }
            if (cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof IOException) {
                // IOException last, because the two above are the interesting cases and this
                // is what a connection dropped mid-answer arrives as.
                return ErrorCode.UPSTREAM_UNAVAILABLE;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    /**
     * Netty's read timeout, matched by name.
     *
     * <p>By name because it is not a {@link TimeoutException} and importing it would put a
     * Netty type in the gateway's own code for one instanceof. The name is part of Netty's
     * public API in every practical sense: it appears in stack traces people have been
     * reading for a decade.
     */
    private static boolean isReadTimeout(Throwable cause) {
        return cause.getClass().getName().endsWith("ReadTimeoutException");
    }

    /**
     * What the gateway itself already concluded, translated into the platform's vocabulary.
     *
     * <p>A response timeout arrives as a 504 with no body: the status is right and the body is
     * the thing missing, which is the whole of this story. Only the three statuses that mean
     * "the service behind this route did not answer" are taken — a 500 the service produced
     * itself is its own answer and is proxied through untouched.
     */
    private static ErrorCode gatewaySaid(Throwable cause) {
        if (!(cause instanceof ResponseStatusException refused)) {
            return null;
        }
        return switch (refused.getStatusCode().value()) {
            case 504 -> ErrorCode.UPSTREAM_TIMEOUT;
            case 502, 503 -> ErrorCode.UPSTREAM_UNAVAILABLE;
            default -> null;
        };
    }

    private Mono<Void> write(ServerWebExchange exchange, ErrorCode code) {
        ProblemDetail body = Problems.of(
                code,
                code == ErrorCode.UPSTREAM_TIMEOUT ? TIMED_OUT : UNAVAILABLE,
                correlationIdOf(exchange),
                List.of());

        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(code.status()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(json.writeValueAsBytes(body));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /**
     * The id this request is being followed by, which the caller is told to quote.
     *
     * <p>Off the attribute rather than the request, because a handler outside the filter chain
     * is given the exchange the container built and not the one the correlation filter mutated.
     * Reading the header here would return the caller's own id when they sent one and nothing
     * at all when they did not, which is the case where quoting an id matters most.
     */
    private static String correlationIdOf(ServerWebExchange exchange) {
        Object stamped = exchange.getAttributes().get(ReactiveCorrelationIdFilter.ATTRIBUTE);
        if (stamped instanceof String correlationId) {
            return correlationId;
        }

        String sent = exchange.getRequest().getHeaders().getFirst(CorrelationContext.HEADER);
        return sent == null ? "" : sent;
    }
}
