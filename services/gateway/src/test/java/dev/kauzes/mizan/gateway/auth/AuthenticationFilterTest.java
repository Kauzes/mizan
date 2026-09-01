package dev.kauzes.mizan.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.identity.CallerIdentity;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the edge has to get right. None of this needs a running platform: a token, a key, and
 * the decision the filter makes about them.
 */
class AuthenticationFilterTest {

    private static final String ISSUER = "https://mizan.local/identity";
    private static final String MERCHANT = "0f5a3b2c-1d4e-4f6a-8b9c-0d1e2f3a4b5c";
    private static final String USER = "9e8d7c6b-5a4f-4e3d-2c1b-0a9f8e7d6c5b";
    private static final String PROTECTED = "/api/v1/merchants/" + MERCHANT;

    /**
     * Built the way the framework builds the one the gateway is given: without the mixin, a
     * problem detail serialises with its extra fields nested, which is not the shape the rest
     * of the platform returns. GatewayProblemRenderingTest holds the real mapper to this.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

    private static final int MAXIMUM_BODY = 1024 * 1024;

    private final RSAKey key = freshKey();
    private final AuthenticationProperties properties =
            new AuthenticationProperties(ISSUER, "http://identity/jwks", null, null);

    private final AuthenticationFilter filter = new AuthenticationFilter(
            new PublicRoutes(),
            new AccessTokenVerifier(keysHolding(key), properties),
            signaturesRefusing(),
            JSON,
            MAXIMUM_BODY);

    @Test
    void refusesARequestCarryingNoToken() throws Exception {
        MockServerWebExchange exchange = request(MockServerHttpRequest.get(PROTECTED));
        RecordingChain chain = new RecordingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.reached).as("nothing should reach a service").isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        JsonNode problem = refusalOf(exchange);
        assertThat(problem.path("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(problem.path("correlationId").asString())
                .as("a refusal is as traceable as any other response")
                .isEqualTo("a-correlation-id");
        assertThat(problem.path("type").asString())
                .isEqualTo("https://mizan.kauzes.dev/errors/unauthorized");
    }

    @Test
    void refusesAnExpiredToken() throws Exception {
        String stale = token(builder -> builder
                .issueTime(Date.from(Instant.now().minus(Duration.ofHours(2))))
                .expirationTime(Date.from(Instant.now().minus(Duration.ofHours(1)))));

        assertRefused(stale);
    }

    @Test
    void refusesATokenSignedByAKeyItDoesNotKnow() throws Exception {
        RSAKey somebodyElse = freshKey();
        String foreign = signedWith(somebodyElse, claims().build());

        assertRefused(foreign);
    }

    @Test
    void refusesATokenIssuedBySomebodyElse() throws Exception {
        assertRefused(token(builder -> builder.issuer("https://not-mizan.example/")));
    }

    @Test
    void refusesSomethingThatIsNotAToken() throws Exception {
        assertRefused("nonsense");
    }

    @Test
    void passesAVerifiedCallerDownstream() throws Exception {
        MockServerWebExchange exchange = request(MockServerHttpRequest.get(PROTECTED)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(builder -> builder)));
        RecordingChain chain = new RecordingChain();

        filter.filter(exchange, chain).block();

        HttpHeaders forwarded = chain.received.getRequest().getHeaders();
        assertThat(forwarded.getFirst(CallerIdentity.USER_HEADER)).isEqualTo(USER);
        assertThat(forwarded.getFirst(CallerIdentity.MERCHANT_HEADER)).isEqualTo(MERCHANT);
        assertThat(forwarded.getFirst(CallerIdentity.ROLES_HEADER)).isEqualTo("OWNER,ANALYST");
    }

    @Test
    void refusesToLetACallerNameTheirOwnMerchant() throws Exception {
        String someoneElsesMerchant = UUID.randomUUID().toString();

        MockServerWebExchange exchange = request(MockServerHttpRequest.get(PROTECTED)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(builder -> builder))
                .header(CallerIdentity.MERCHANT_HEADER, someoneElsesMerchant)
                .header(CallerIdentity.USER_HEADER, "somebody-else")
                .header(CallerIdentity.ROLES_HEADER, "OWNER,ADMIN,ANALYST,VIEWER"));
        RecordingChain chain = new RecordingChain();

        filter.filter(exchange, chain).block();

        HttpHeaders forwarded = chain.received.getRequest().getHeaders();
        assertThat(forwarded.getFirst(CallerIdentity.MERCHANT_HEADER))
                .as("the merchant comes from the token, never from the request")
                .isEqualTo(MERCHANT);
        assertThat(forwarded.getFirst(CallerIdentity.USER_HEADER)).isEqualTo(USER);
        assertThat(forwarded.getFirst(CallerIdentity.ROLES_HEADER)).isEqualTo("OWNER,ANALYST");
    }

    @Test
    void stripsClaimedIdentityFromPublicRoutesToo() {
        MockServerWebExchange exchange = request(MockServerHttpRequest.post("/api/v1/tokens")
                .header(CallerIdentity.USER_HEADER, "somebody")
                .header(CallerIdentity.MERCHANT_HEADER, "some-merchant")
                .header(CallerIdentity.ROLES_HEADER, "OWNER"));
        RecordingChain chain = new RecordingChain();

        filter.filter(exchange, chain).block();

        HttpHeaders forwarded = chain.received.getRequest().getHeaders();
        assertThat(forwarded.containsHeader(CallerIdentity.USER_HEADER))
                .as("a route being public does not make a forged identity acceptable")
                .isFalse();
        assertThat(forwarded.containsHeader(CallerIdentity.MERCHANT_HEADER)).isFalse();
        assertThat(forwarded.containsHeader(CallerIdentity.ROLES_HEADER)).isFalse();
    }

    @Test
    void letsSigningInThroughWithoutAToken() {
        RecordingChain chain = new RecordingChain();

        filter.filter(request(MockServerHttpRequest.post("/api/v1/tokens")), chain).block();

        assertThat(chain.reached).as("nobody has a token before they sign in").isTrue();
    }

    @Test
    void saysTheProblemIsOursWhenTheKeysCannotBeFetched() throws Exception {
        AuthenticationFilter unreachable = new AuthenticationFilter(
                new PublicRoutes(),
                new AccessTokenVerifier(keysThatCannotBeReached(), properties),
                signaturesRefusing(),
                JSON,
                MAXIMUM_BODY);

        MockServerWebExchange exchange = request(MockServerHttpRequest.get(PROTECTED)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(builder -> builder)));

        unreachable.filter(exchange, new RecordingChain()).block();

        assertThat(exchange.getResponse().getStatusCode().value())
                .as("perfectly good credentials should not be reported as bad ones")
                .isEqualTo(503);
        assertThat(refusalOf(exchange).path("code").asString()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    private void assertRefused(String token) throws Exception {
        MockServerWebExchange exchange = request(MockServerHttpRequest.get(PROTECTED)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        RecordingChain chain = new RecordingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.reached).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(refusalOf(exchange).path("detail").asString())
                .as("every bad token is refused in the same words")
                .isEqualTo("The credentials are not valid.");
    }

    private static MockServerWebExchange request(MockServerHttpRequest.BaseBuilder<?> builder) {
        return MockServerWebExchange.from(
                builder.header(CorrelationContext.HEADER, "a-correlation-id"));
    }

    private static JsonNode refusalOf(MockServerWebExchange exchange) throws Exception {
        return JSON.readTree(exchange.getResponse().getBodyAsString().block());
    }

    private String token(java.util.function.UnaryOperator<JWTClaimsSet.Builder> adjust) {
        return signedWith(key, adjust.apply(claims()).build());
    }

    private static JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(USER)
                .claim(CallerIdentity.MERCHANT_CLAIM, MERCHANT)
                .claim(CallerIdentity.ROLES_CLAIM, List.of("OWNER", "ANALYST"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))));
    }

    private static String signedWith(RSAKey signingKey, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(signingKey.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static RSAKey freshKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            RSAKey withoutId = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey(pair.getPrivate())
                    .build();
            return new RSAKey.Builder(withoutId)
                    .keyID(withoutId.computeThumbprint().toString())
                    .build();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Stands in for the key set identity publishes, without publishing one. */
    private SigningKeys keysHolding(RSAKey held) {
        return new SigningKeys(null, properties, java.time.Clock.systemUTC()) {
            @Override
            public Mono<Optional<JWK>> withId(String keyId) {
                return Mono.just(
                        held.getKeyID().equals(keyId) ? Optional.of(held.toPublicJWK())
                                : Optional.empty());
            }
        };
    }

    /** No test here signs a request; the signed path has a test class of its own. */
    private SignedRequestVerifier signaturesRefusing() {
        return new SignedRequestVerifier(
                (org.springframework.web.reactive.function.client.WebClient) null,
                "http://identity/verify") {
            @Override
            public Mono<VerifiedCaller> verify(Verification presented) {
                return Mono.error(new dev.kauzes.mizan.common.error.UnauthorizedException(
                        "The credentials are not valid."));
            }
        };
    }

    private SigningKeys keysThatCannotBeReached() {
        return new SigningKeys(null, properties, java.time.Clock.systemUTC()) {
            @Override
            public Mono<Optional<JWK>> withId(String keyId) {
                return Mono.error(new IllegalStateException("identity is not answering"));
            }
        };
    }

    private static final class RecordingChain implements WebFilterChain {

        private ServerWebExchange received;
        private boolean reached;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.received = exchange;
            this.reached = true;
            return Mono.empty();
        }
    }
}
