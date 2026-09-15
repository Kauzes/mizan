package dev.kauzes.mizan.gateway.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one allowance every merchant gets. ADR 0053.
 *
 * <p>Spring Cloud Gateway's own Redis limiter, a token bucket kept in Redis and updated by one
 * script, so two gateway pods taking a request for the same merchant at the same instant cannot
 * both spend the last token. Declaring it here replaces the unconfigured one the gateway would
 * otherwise create.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfiguration {

    @Bean
    RedisRateLimiter merchantRateLimiter(
            @Value("${mizan.rate-limit.requests-per-second:100}") int requestsPerSecond,
            @Value("${mizan.rate-limit.burst:200}") long burst) {

        RedisRateLimiter limiter = new RedisRateLimiter(requestsPerSecond, burst);
        // Its X-RateLimit-* headers describe a token bucket in the gateway's own vocabulary. What
        // a merchant needs is when to send again, which MerchantRateLimits says as Retry-After.
        limiter.setIncludeHeaders(false);
        return limiter;
    }
}
