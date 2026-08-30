package dev.kauzes.mizan.gateway.auth;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.error.UnauthorizedException;
import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.web.Problems;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Where the platform decides who is calling.
 *
 * <p>Authentication happens once, here, so no service behind the gateway has to work out
 * whether a caller is real. What a service receives is a caller that has already been
 * established, on headers it can trust.
 *
 * <p>Those headers are trustworthy only because this filter removes whatever arrived under
 * the same names first, on every route including the public ones. Setting them without
 * stripping them would mean anyone could claim to be anyone by typing a header, which is a
 * worse hole than having no authentication at all — it would look authenticated.
 */
@Component
public class AccessTokenAuthenticationFilter implements WebFilter, Ordered {

    private static final Logger log =
            LoggerFactory.getLogger(AccessTokenAuthenticationFilter.class);

    private static final String BEARER = "Bearer ";

    private final PublicRoutes publicRoutes;
    private final AccessTokenVerifier verifier;
    private final ObjectMapper json;

    public AccessTokenAuthenticationFilter(
            PublicRoutes publicRoutes, AccessTokenVerifier verifier, ObjectMapper json) {
        this.publicRoutes = publicRoutes;
        this.verifier = verifier;
        this.json = json;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerWebExchange stripped = withoutClaimedIdentity(exchange);

        if (publicRoutes.isPublic(stripped.getRequest())) {
            return chain.filter(stripped);
        }

        String authorization =
                stripped.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return refuse(stripped, new UnauthorizedException("This endpoint needs an access token."));
        }

        return verifier
                .verify(authorization.substring(BEARER.length()).trim())
                .flatMap(caller -> chain.filter(carrying(stripped, caller)))
                .onErrorResume(MizanException.class, refusal -> refuse(stripped, refusal));
    }

    /**
     * Removes any identity headers the caller sent. Done before the public check as well,
     * because a public route forwards downstream too.
     */
    private static ServerWebExchange withoutClaimedIdentity(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        boolean claimed = CallerIdentity.HEADERS.stream()
                .anyMatch(header -> request.getHeaders().containsHeader(header));

        if (!claimed) {
            return exchange;
        }

        log.warn(
                "a request to {} arrived carrying identity headers, which were removed",
                request.getPath().value());
        return exchange.mutate()
                .request(builder ->
                        builder.headers(headers -> CallerIdentity.HEADERS.forEach(headers::remove)))
                .build();
    }

    private static ServerWebExchange carrying(ServerWebExchange exchange, VerifiedCaller caller) {
        return exchange.mutate()
                .request(builder -> builder.headers(headers -> {
                    headers.set(CallerIdentity.USER_HEADER, caller.userId());
                    headers.set(CallerIdentity.MERCHANT_HEADER, caller.merchantId());
                    headers.set(CallerIdentity.ROLES_HEADER, caller.rolesHeader());
                }))
                .build();
    }

    /** The same problem detail a service would have returned, so a caller parses one shape. */
    private Mono<Void> refuse(ServerWebExchange exchange, MizanException refusal) {
        ErrorCode code = refusal.errorCode();
        ProblemDetail body = Problems.of(
                code,
                refusal.getMessage(),
                correlationIdOf(exchange),
                List.of());

        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(code.status()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        byte[] rendered;
        try {
            rendered = json.writeValueAsBytes(body);
        } catch (Exception unrenderable) {
            return Mono.error(unrenderable);
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(rendered);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /**
     * Read off the request rather than out of the logging context: the gateway is reactive,
     * and the id was stamped onto the request by the filter ahead of this one.
     */
    private static String correlationIdOf(ServerWebExchange exchange) {
        String correlationId =
                exchange.getRequest().getHeaders().getFirst(CorrelationContext.HEADER);
        return correlationId == null ? "" : correlationId;
    }

    @Override
    public int getOrder() {
        // After the correlation id is stamped, so a refusal can carry it.
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
