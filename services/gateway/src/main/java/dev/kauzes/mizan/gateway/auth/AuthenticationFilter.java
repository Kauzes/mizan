package dev.kauzes.mizan.gateway.auth;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.error.UnauthorizedException;
import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.RequestSigning;
import dev.kauzes.mizan.common.web.Problems;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Where the platform decides who is calling.
 *
 * <p>Two kinds of caller. A person signed in to the console sends a bearer token, verified
 * here against the key identity publishes. A merchant's server sends a key and a signature
 * over the request, verified by identity itself. Both end in the same place: a caller
 * established once, at the edge, and passed on as headers a service can trust.
 *
 * <p>Those headers are trustworthy only because this filter removes whatever arrived under
 * the same names first, on every route including the public ones. Setting them without
 * stripping them would mean anyone could claim to be anyone by typing a header, which is a
 * worse hole than having no authentication at all — it would look authenticated.
 *
 * <p>The credential headers do not travel onwards either. A service has no use for them, and
 * a credential that stops at the edge cannot be logged by six other places.
 */
@Component
public class AuthenticationFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    private static final String BEARER = "Bearer ";
    private static final String REFUSED = "The credentials are not valid.";

    private static final List<String> CREDENTIAL_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION,
            RequestSigning.KEY_HEADER,
            RequestSigning.SIGNATURE_HEADER,
            RequestSigning.TIMESTAMP_HEADER);

    private final PublicRoutes publicRoutes;
    private final AccessTokenVerifier tokens;
    private final SignedRequestVerifier signatures;
    private final ObjectMapper json;
    private final int maximumSignedBody;

    public AuthenticationFilter(
            PublicRoutes publicRoutes,
            AccessTokenVerifier tokens,
            SignedRequestVerifier signatures,
            ObjectMapper json,
            @Value("${mizan.security.api-keys.maximum-signed-body:1048576}") int maximumSignedBody) {

        this.publicRoutes = publicRoutes;
        this.tokens = tokens;
        this.signatures = signatures;
        this.json = json;
        this.maximumSignedBody = maximumSignedBody;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerWebExchange stripped = withoutClaimedIdentity(exchange);

        if (publicRoutes.isPublic(stripped.getRequest())) {
            return chain.filter(stripped);
        }

        if (stripped.getRequest().getHeaders().containsHeader(RequestSigning.KEY_HEADER)) {
            return signed(stripped, chain);
        }
        return bearer(stripped, chain);
    }

    private Mono<Void> bearer(ServerWebExchange exchange, WebFilterChain chain) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return refuse(exchange, new UnauthorizedException("This endpoint needs an access token."));
        }

        return tokens.verify(authorization.substring(BEARER.length()).trim())
                .flatMap(caller -> chain.filter(carrying(exchange, caller)))
                .onErrorResume(MizanException.class, refusal -> refuse(exchange, refusal));
    }

    /**
     * The body is read here because the signature covers it, and read once: what is forwarded
     * is the same bytes replayed, so a service downstream sees the request the merchant sent
     * and the signature was checked against exactly what will be acted on.
     */
    private Mono<Void> signed(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();

        String keyId = headers.getFirst(RequestSigning.KEY_HEADER);
        String signature = headers.getFirst(RequestSigning.SIGNATURE_HEADER);
        String timestamp = headers.getFirst(RequestSigning.TIMESTAMP_HEADER);

        long signedAt;
        try {
            signedAt = Long.parseLong(timestamp == null ? "" : timestamp.trim());
        } catch (NumberFormatException notASecond) {
            return refuse(exchange, new UnauthorizedException(REFUSED));
        }
        if (signature == null || signature.isBlank()) {
            return refuse(exchange, new UnauthorizedException(REFUSED));
        }

        return DataBufferUtils.join(request.getBody(), maximumSignedBody)
                .map(AuthenticationFilter::drain)
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> signatures
                        .verify(new SignedRequestVerifier.Verification(
                                keyId,
                                signature,
                                request.getMethod().name(),
                                request.getPath().value(),
                                signedAt,
                                RequestSigning.bodyHash(body)))
                        .flatMap(caller -> chain.filter(carrying(replaying(exchange, body), caller))))
                .onErrorResume(MizanException.class, refusal -> refuse(exchange, refusal))
                .onErrorResume(
                        failure -> !(failure instanceof MizanException),
                        failure -> refuse(
                                exchange,
                                new MizanException(
                                        ErrorCode.UNPROCESSABLE,
                                        "A signed request must be small enough to read in one "
                                                + "piece.",
                                        failure)));
    }

    private static byte[] drain(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }

    /** Hands the body back to whoever reads it next, since it has already been consumed. */
    private static ServerWebExchange replaying(ServerWebExchange exchange, byte[] body) {
        ServerHttpRequest replayed = new ServerHttpRequestDecorator(exchange.getRequest()) {

            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() ->
                        Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
            }
        };
        return exchange.mutate().request(replayed).build();
    }

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
                    CREDENTIAL_HEADERS.forEach(headers::remove);
                    headers.set(CallerIdentity.USER_HEADER, caller.userId());
                    headers.set(CallerIdentity.MERCHANT_HEADER, caller.merchantId());
                    headers.set(CallerIdentity.ROLES_HEADER, caller.rolesHeader());
                    headers.set(CallerIdentity.PRINCIPAL_HEADER, caller.principal().name());
                }))
                .build();
    }

    /** The same problem detail a service would have returned, so a caller parses one shape. */
    private Mono<Void> refuse(ServerWebExchange exchange, MizanException refusal) {
        ErrorCode code = refusal.errorCode();
        ProblemDetail body =
                Problems.of(code, refusal.getMessage(), correlationIdOf(exchange), List.of());

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
