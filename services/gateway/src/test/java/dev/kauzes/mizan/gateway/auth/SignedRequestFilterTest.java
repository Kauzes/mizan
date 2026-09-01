package dev.kauzes.mizan.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.error.UnauthorizedException;
import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.RequestSigning;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The signed half of the edge.
 *
 * <p>The stub below does the real arithmetic rather than agreeing to whatever it is handed:
 * it recomputes the signature from the parts the gateway sends. So these tests are about the
 * gateway's own work — which method, which path, which bytes — and a gateway that hashed the
 * wrong body or the wrong path would fail them.
 */
class SignedRequestFilterTest {

    private static final String KEY_ID = "mzk_testkeyidentifier";
    private static final String SECRET = "mzs_a-secret-only-the-merchant-and-identity-know";
    private static final String PROTECTED = "/api/v1/merchants/" + UUID.randomUUID();
    private static final UUID PRINCIPAL = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

    private final AuthenticationFilter filter = new AuthenticationFilter(
            new PublicRoutes(),
            new AccessTokenVerifier(
                    keysThatAreNeverAsked(),
                    new AuthenticationProperties("https://mizan.local/identity", "http://x", null, null)),
            recomputingVerifier(),
            JSON,
            1024 * 1024);

    @Test
    void passesASignedRequestThrough() {
        String body = "{\"amount\":1200}";
        RecordingChain chain = new RecordingChain();

        filter.filter(signed("POST", PROTECTED, body, Instant.now().getEpochSecond()), chain)
                .block();

        assertThat(chain.reached).isTrue();
        HttpHeaders forwarded = chain.received.getRequest().getHeaders();
        assertThat(forwarded.getFirst(CallerIdentity.USER_HEADER))
                .isEqualTo(PRINCIPAL.toString());
        assertThat(forwarded.getFirst(CallerIdentity.MERCHANT_HEADER))
                .isEqualTo(MERCHANT.toString());
        assertThat(forwarded.getFirst(CallerIdentity.ROLES_HEADER)).isEqualTo("ADMIN");
        assertThat(forwarded.getFirst(CallerIdentity.PRINCIPAL_HEADER))
                .as("a service should be able to tell a server from a person")
                .isEqualTo("API_KEY");
    }

    @Test
    void leavesTheBodyReadableForTheServiceBehindIt() {
        String body = "{\"amount\":1200}";
        RecordingChain chain = new RecordingChain();

        filter.filter(signed("POST", PROTECTED, body, Instant.now().getEpochSecond()), chain)
                .block();

        assertThat(bodyOf(chain.received))
                .as("the request the service acts on is the one that was signed")
                .isEqualTo(body);
    }

    @Test
    void doesNotForwardTheCredentials() {
        RecordingChain chain = new RecordingChain();

        filter.filter(signed("POST", PROTECTED, "{}", Instant.now().getEpochSecond()), chain)
                .block();

        HttpHeaders forwarded = chain.received.getRequest().getHeaders();
        assertThat(forwarded.containsHeader(RequestSigning.KEY_HEADER)).isFalse();
        assertThat(forwarded.containsHeader(RequestSigning.SIGNATURE_HEADER)).isFalse();
        assertThat(forwarded.containsHeader(RequestSigning.TIMESTAMP_HEADER)).isFalse();
    }

    @Test
    void refusesABodyThatChangedAfterItWasSigned() {
        long now = Instant.now().getEpochSecond();
        String signature = signature("POST", PROTECTED, "{\"amount\":1200}", now);

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(PROTECTED)
                .header(RequestSigning.KEY_HEADER, KEY_ID)
                .header(RequestSigning.SIGNATURE_HEADER, signature)
                .header(RequestSigning.TIMESTAMP_HEADER, String.valueOf(now))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\":120000}"));

        RecordingChain chain = new RecordingChain();
        filter.filter(exchange, chain).block();

        assertThat(chain.reached).as("nothing should reach a service").isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void refusesASignatureAimedAtADifferentEndpoint() {
        long now = Instant.now().getEpochSecond();
        String signature = signature("POST", "/api/v1/payments", "{}", now);

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(PROTECTED)
                .header(RequestSigning.KEY_HEADER, KEY_ID)
                .header(RequestSigning.SIGNATURE_HEADER, signature)
                .header(RequestSigning.TIMESTAMP_HEADER, String.valueOf(now))
                .body("{}"));

        RecordingChain chain = new RecordingChain();
        filter.filter(exchange, chain).block();

        assertThat(chain.reached).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void refusesASignatureMadeForADifferentMethod() {
        long now = Instant.now().getEpochSecond();
        String signature = signature("DELETE", PROTECTED, "{}", now);

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(PROTECTED)
                .header(RequestSigning.KEY_HEADER, KEY_ID)
                .header(RequestSigning.SIGNATURE_HEADER, signature)
                .header(RequestSigning.TIMESTAMP_HEADER, String.valueOf(now))
                .body("{}"));

        RecordingChain chain = new RecordingChain();
        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void refusesATimestampThatIsNotOne() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(PROTECTED)
                .header(RequestSigning.KEY_HEADER, KEY_ID)
                .header(RequestSigning.SIGNATURE_HEADER, "whatever")
                .header(RequestSigning.TIMESTAMP_HEADER, "the-day-before-yesterday")
                .body("{}"));

        RecordingChain chain = new RecordingChain();
        filter.filter(exchange, chain).block();

        assertThat(chain.reached).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void signsAGetWithNoBodyJustTheSame() {
        long now = Instant.now().getEpochSecond();
        RecordingChain chain = new RecordingChain();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(PROTECTED)
                .header(RequestSigning.KEY_HEADER, KEY_ID)
                .header(RequestSigning.SIGNATURE_HEADER, signature("GET", PROTECTED, "", now))
                .header(RequestSigning.TIMESTAMP_HEADER, String.valueOf(now)));

        filter.filter(exchange, chain).block();

        assertThat(chain.reached).as("an empty body still has a digest").isTrue();
    }

    private static MockServerWebExchange signed(
            String method, String path, String body, long timestamp) {

        return MockServerWebExchange.from(
                MockServerHttpRequest.method(
                                org.springframework.http.HttpMethod.valueOf(method), path)
                        .header(RequestSigning.KEY_HEADER, KEY_ID)
                        .header(
                                RequestSigning.SIGNATURE_HEADER,
                                signature(method, path, body, timestamp))
                        .header(RequestSigning.TIMESTAMP_HEADER, String.valueOf(timestamp))
                        .header(CorrelationContext.HEADER, "a-correlation-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));
    }

    private static MockServerWebExchange exchange(MockServerHttpRequest request) {
        return MockServerWebExchange.from(request);
    }

    /** A request with no body is built from the builder itself, not from {@code body}. */
    private static MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request);
    }

    private static String signature(String method, String path, String body, long timestamp) {
        return RequestSigning.sign(
                SECRET,
                RequestSigning.canonicalRequest(
                        method,
                        path,
                        timestamp,
                        RequestSigning.bodyHash(body.getBytes(StandardCharsets.UTF_8))));
    }

    /** Identity, in miniature: it recomputes rather than agreeing. */
    private static SignedRequestVerifier recomputingVerifier() {
        return new SignedRequestVerifier(
                (org.springframework.web.reactive.function.client.WebClient) null,
                "http://identity/verify") {

            @Override
            public Mono<VerifiedCaller> verify(Verification presented) {
                String expected = RequestSigning.sign(
                        SECRET,
                        RequestSigning.canonicalRequest(
                                presented.method(),
                                presented.path(),
                                presented.timestamp(),
                                presented.bodyHash()));

                if (!KEY_ID.equals(presented.keyId())
                        || !RequestSigning.matches(expected, presented.signature())) {
                    return Mono.error(
                            new UnauthorizedException("The credentials are not valid."));
                }
                return Mono.just(new VerifiedCaller(
                        PRINCIPAL.toString(),
                        MERCHANT.toString(),
                        List.of("ADMIN"),
                        VerifiedCaller.Principal.API_KEY));
            }
        };
    }

    private static SigningKeys keysThatAreNeverAsked() {
        return new SigningKeys(
                (org.springframework.web.reactive.function.client.WebClient) null,
                new AuthenticationProperties("https://mizan.local/identity", "http://x", null, null),
                java.time.Clock.systemUTC()) {

            @Override
            public Mono<java.util.Optional<com.nimbusds.jose.jwk.JWK>> withId(String keyId) {
                return Mono.error(new IllegalStateException("no token should be verified here"));
            }
        };
    }

    private static String bodyOf(ServerWebExchange exchange) {
        DataBuffer joined = DataBufferUtils.join(exchange.getRequest().getBody()).block();
        assertThat(joined).isNotNull();
        byte[] bytes = new byte[joined.readableByteCount()];
        joined.read(bytes);
        DataBufferUtils.release(joined);
        return new String(bytes, StandardCharsets.UTF_8);
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
