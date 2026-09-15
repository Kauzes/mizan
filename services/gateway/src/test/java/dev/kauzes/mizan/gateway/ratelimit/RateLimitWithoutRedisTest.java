package dev.kauzes.mizan.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import dev.kauzes.mizan.gateway.auth.AccessTokenVerifier;
import dev.kauzes.mizan.gateway.auth.VerifiedCaller;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * Redis being down turns the rate limit off, not the platform. ADR 0053.
 *
 * <p>No Redis at all: the gateway is pointed at a port nothing listens on. Every request, far past
 * the allowance, still reaches the service behind the route. The alternative — refusing when the
 * count cannot be read — makes a cache outage refuse every merchant everything.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "mizan.rate-limit.requests-per-second=1",
            "mizan.rate-limit.burst=1"
        })
class RateLimitWithoutRedisTest {

    private static final HttpServer UPSTREAM;
    private static final int NOTHING_LISTENING;

    static {
        try (ServerSocket briefly = new ServerSocket(0)) {
            NOTHING_LISTENING = briefly.getLocalPort();
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException couldNotListen) {
            throw new IllegalStateException(couldNotListen);
        }
        UPSTREAM.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        UPSTREAM.setExecutor(Executors.newFixedThreadPool(4));
        UPSTREAM.start();
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> NOTHING_LISTENING);
        String prefix = "spring.cloud.gateway.server.webflux.routes[0]";
        registry.add(prefix + ".id", () -> "payments");
        registry.add(prefix + ".predicates[0]", () -> "Path=/api/v1/merchants/*/payments");
        registry.add(prefix + ".uri", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
    }

    @MockitoBean
    private AccessTokenVerifier tokens;

    @Value("${local.server.port}")
    private int port;

    @BeforeEach
    void theTokenSaysWhichMerchant() {
        when(tokens.verify(anyString())).thenAnswer(call -> Mono.just(VerifiedCaller.user(
                UUID.randomUUID().toString(),
                ((String) call.getArgument(0)).substring("merchant-".length()),
                List.of("ADMIN"))));
    }

    @Test
    void everyRequestIsServedWhenTheCountCannotBeRead() throws Exception {
        String merchant = UUID.randomUUID().toString();
        HttpClient http = HttpClient.newHttpClient();

        for (int i = 0; i < 20; i++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + port + "/api/v1/merchants/" + merchant + "/payments"))
                    .header("Authorization", "Bearer merchant-" + merchant)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                    .as("request %d of 20, twenty times an allowance of one", i + 1)
                    .isEqualTo(204);
        }
    }
}
