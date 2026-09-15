package dev.kauzes.mizan.gateway.ratelimit;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.web.Problems;
import dev.kauzes.mizan.common.web.ReactiveCorrelationIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * One merchant's burst cannot starve the others. ADR 0053.
 *
 * <p>Every request a merchant sends spends from an allowance that refills at a fixed rate, and a
 * request with nothing left to spend is refused at the edge before any service does work for it.
 * So a merchant with a runaway retry loop uses up their own allowance, and the payment services
 * behind the gateway, and every other merchant using them, never see the flood.
 *
 * <h2>Keyed on who the gateway verified, not on anything the caller says</h2>
 *
 * <p>Runs after {@code AuthenticationFilter}, which has already removed any identity headers the
 * caller sent and set its own. So the merchant counted is the merchant whose token or key was
 * verified. Keyed on a path segment instead, a merchant could spread a flood across other
 * merchants' ids and have each request refused for the wrong reason, after spending somebody
 * else's allowance.
 *
 * <p>A request with no verified merchant is not counted here. That is a public route — signing
 * in, registering — and has no merchant to charge it to yet.
 *
 * <h2>Redis being unreachable lets requests through</h2>
 *
 * <p>The limiter answers "allowed" when it cannot ask Redis. A rate limit protects the platform
 * from one merchant; a rate limit that fails closed turns a cache outage into every merchant
 * being refused everything, which is the outage it existed to prevent.
 */
@Component
public class MerchantRateLimits implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MerchantRateLimits.class);

    /**
     * The one bucket family. The limiter is keyed by route and caller; every route shares one
     * allowance per merchant, because a merchant flooding refunds starves others as surely as one
     * flooding payments does.
     */
    static final String ALLOWANCE = "merchant";

    private final RedisRateLimiter limiter;
    private final ObjectMapper json;
    private final int requestsPerSecond;
    private final Counter refused;

    public MerchantRateLimits(
            RedisRateLimiter limiter,
            ObjectMapper json,
            MeterRegistry meters,
            @Value("${mizan.rate-limit.requests-per-second:100}") int requestsPerSecond) {

        this.limiter = limiter;
        this.json = json;
        this.requestsPerSecond = requestsPerSecond;
        // No merchant tag. Which merchant is in the log; a tag per merchant is a series per merchant.
        this.refused = Counter.builder("mizan.gateway.requests.rate.limited")
                .description("Requests refused because the merchant had used its allowance")
                .register(meters);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String merchant = exchange.getRequest().getHeaders().getFirst(CallerIdentity.MERCHANT_HEADER);
        if (merchant == null || merchant.isBlank()) {
            return chain.filter(exchange);
        }

        return limiter.isAllowed(ALLOWANCE, merchant).flatMap(answer -> {
            if (answer.isAllowed()) {
                return chain.filter(exchange);
            }
            refused.increment();
            log.info("merchant {} is over its allowance of {} a second; refused {} {}",
                    merchant,
                    requestsPerSecond,
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value());
            return refuse(exchange);
        });
    }

    private Mono<Void> refuse(ServerWebExchange exchange) {
        ProblemDetail body = Problems.of(
                ErrorCode.RATE_LIMITED,
                "This merchant is sending more than " + requestsPerSecond + " requests a second. "
                        + "Nothing was done. Wait the number of seconds in Retry-After and send "
                        + "it again.",
                correlationIdOf(exchange),
                List.of());

        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(ErrorCode.RATE_LIMITED.status()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        // Whole seconds are all the header allows. At any rate of one a second or more a token is
        // back within a second, so one is always enough and never a long wait.
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(json.writeValueAsBytes(body));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private static String correlationIdOf(ServerWebExchange exchange) {
        Object stamped = exchange.getAttributes().get(ReactiveCorrelationIdFilter.ATTRIBUTE);
        if (stamped instanceof String correlationId) {
            return correlationId;
        }
        String sent = exchange.getRequest().getHeaders().getFirst(CorrelationContext.HEADER);
        return sent == null ? "" : sent;
    }

    @Override
    public int getOrder() {
        // Straight after AuthenticationFilter (+20): the merchant is known, and nothing has yet
        // done any work that a refused request would waste.
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }
}
