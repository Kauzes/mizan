package dev.kauzes.mizan.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * What a caller is told when the service behind a route cannot answer.
 *
 * <p>Found while writing MIZ-26's smoke check: with a service stopped, the gateway answered
 * with Spring's own error body, which carries no {@code code} and no correlation id. The whole
 * platform documents that a caller should branch on the code, so the one moment they most need
 * to tell "try again" from "do not" was the moment the contract was not honoured.
 *
 * <p>Two real sockets rather than mocks. What arrives at the gateway when a connection is
 * refused, or when an answer never comes, is decided by the network and the HTTP client rather
 * than by anything this project wrote — a test that threw the exception itself would be
 * asserting a guess about which exception that is.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Long enough that a healthy service is never cut off, short enough to wait for.
            "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s"
        })
class UpstreamFailureTest {

    /** A port with nothing behind it. Connecting there is refused immediately. */
    private static int deadPort;

    /** And one that accepts a connection and then says nothing at all, ever. */
    private static ServerSocket silent;

    @BeforeAll
    static void findSomewhereNothingIsListening() throws IOException {
        try (ServerSocket briefly = new ServerSocket(0)) {
            deadPort = briefly.getLocalPort();
        }

        silent = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!silent.isClosed()) {
                try {
                    // Accepted and then left. A merchant waiting on a service that has stopped
                    // answering is the case a response timeout exists for.
                    silent.accept();
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    @AfterAll
    static void stopListening() throws IOException {
        silent.close();
    }

    /**
     * Two routes, and only these two.
     *
     * <p>A list binds from one property source rather than being merged across them, so
     * declaring routes here replaces the configured table entirely rather than adding to it.
     * That is the better arrangement for this test anyway: what is being asserted is what the
     * gateway says when the thing behind a route is not there, and which routes exist is
     * {@link GatewayRouteDefinitionTests}' question.
     */
    @DynamicPropertySource
    static void pointTwoRoutesAtNothing(DynamicPropertyRegistry registry) {
        // Both are public paths, so what is asserted is the answer rather than a 401 on the
        // way to it. Signing in is also what a merchant is most likely to be doing when they
        // first notice the platform is unwell.
        route(registry, 0, "nothing-listening", "/api/v1/tokens", "127.0.0.1:" + deadPort);
        route(registry, 1, "nothing-answering", "/api/v1/tokens/refresh", "127.0.0.1:PORT");
    }

    private static void route(
            DynamicPropertyRegistry registry, int index, String id, String path, String target) {

        String prefix = "spring.cloud.gateway.server.webflux.routes[" + index + "]";
        registry.add(prefix + ".id", () -> id);
        registry.add(prefix + ".predicates[0]", () -> "Path=" + path);
        registry.add(
                prefix + ".uri",
                () -> "http://"
                        + target.replace("PORT", String.valueOf(silent.getLocalPort())));
    }

    private WebTestClient client;

    @BeforeEach
    void patientClient(
            @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port) {
        // Bound to the running gateway rather than to a handler, because the answer being
        // asserted is written by an exception handler outside the handler chain.
        //
        // Patient on purpose: the gateway's response timeout is a second, and a client that
        // gave up first would be asserting its own timeout rather than the gateway's answer.
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    void theRoutesThisTestAddedAreTheOnlyOnesAnswering(
            @Autowired org.springframework.cloud.gateway.route.RouteDefinitionLocator routes) {

        // Not really about upstream failures. It is about this file: if these two ever stopped
        // replacing the configured table, everything below would be reaching the real
        // identity-service and passing for a reason that has nothing to do with the change.
        assertThat(routes.getRouteDefinitions().collectList().block())
                .extracting(definition -> definition.getId())
                .containsExactlyInAnyOrder("nothing-listening", "nothing-answering");
    }

    @Test
    void aServiceThatIsNotThereIsAnsweredAsUnavailable() {
        signIn()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("UPSTREAM_UNAVAILABLE")
                .jsonPath("$.type")
                .isEqualTo("https://mizan.kauzes.dev/errors/upstream-unavailable")
                .jsonPath("$.title")
                .isEqualTo("upstream-unavailable")
                .jsonPath("$.correlationId")
                .value(id -> assertThat((String) id)
                        .as("a caller is told to quote this, so there has to be one even when "
                                + "they did not send one themselves")
                        .isNotEmpty())
                .jsonPath("$.timestamp")
                .exists();
    }

    @Test
    void andOneThatStopsAnsweringIsAnsweredAsATimeout() {
        client.post()
                .uri("/api/v1/tokens/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refreshToken\":\"whatever\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(504)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("UPSTREAM_TIMEOUT")
                .jsonPath("$.detail")
                .value(detail -> assertThat((String) detail)
                        .as("whether it acted is genuinely unknown, and a caller not told that "
                                + "will either give up or charge somebody twice")
                        .contains("unknown"));
    }

    @Test
    void andNothingAboutThePlatformLeaksInTheAnswer() {
        String body = new String(signIn()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .returnResult()
                .getResponseBodyContent());

        // A merchant cannot act on a host and a port, and somebody probing the platform should
        // not be handed its shape by an error message.
        assertThat(body)
                .doesNotContain("127.0.0.1")
                .doesNotContain(String.valueOf(deadPort))
                .doesNotContain("Connection refused")
                .doesNotContain("Exception")
                .doesNotContain("java.")
                .doesNotContain("reactor.")
                .doesNotContain("requestId");
    }

    @Test
    void andTheCallersOwnCorrelationIdIsTheOneComingBack() {
        client.post()
                .uri("/api/v1/tokens")
                .header(CorrelationContext.HEADER, "11111111-2222-3333-4444-555555555555")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"someone@mizan.local\",\"password\":\"whatever\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.correlationId")
                .isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    private WebTestClient.ResponseSpec signIn() {
        return client.post()
                .uri("/api/v1/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"someone@mizan.local\",\"password\":\"whatever\"}")
                .exchange();
    }
}
