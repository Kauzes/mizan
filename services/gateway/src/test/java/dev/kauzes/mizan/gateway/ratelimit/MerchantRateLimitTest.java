package dev.kauzes.mizan.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import dev.kauzes.mizan.gateway.auth.AccessTokenVerifier;
import dev.kauzes.mizan.gateway.auth.VerifiedCaller;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * One merchant's burst cannot starve the others. ADR 0053.
 *
 * <p>A real Redis, the image Compose runs, and a real gateway on a real port, because what is being
 * claimed is about requests competing: the token bucket is a script inside Redis, and whether two
 * merchants' requests interfere is decided there and on the gateway's event loop, not in anything a
 * mock could stand in for. The one thing faked is who is calling — token verification has its own
 * tests — so each request says which merchant it is.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Low enough to exceed from a test in a second, high enough that a steady caller at a
            // few requests a second never is.
            "mizan.rate-limit.requests-per-second=5",
            "mizan.rate-limit.burst=10"
        })
class MerchantRateLimitTest {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(System.getProperty("mizan.env.REDIS_IMAGE")))
                    .withExposedPorts(6379);

    /** Stands in for payment-service: answers every request at once, and counts them. */
    private static final HttpServer UPSTREAM;
    private static final AtomicInteger REACHED = new AtomicInteger();

    static {
        REDIS.start();
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException couldNotListen) {
            throw new IllegalStateException(couldNotListen);
        }
        UPSTREAM.createContext("/", exchange -> {
            REACHED.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        UPSTREAM.setExecutor(Executors.newFixedThreadPool(16));
        UPSTREAM.start();
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
        REDIS.stop();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // One route, replacing the configured table, pointed at the stand-in.
        String prefix = "spring.cloud.gateway.server.webflux.routes[0]";
        registry.add(prefix + ".id", () -> "payments");
        registry.add(prefix + ".predicates[0]", () -> "Path=/api/v1/merchants/*/payments");
        registry.add(prefix + ".uri", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
    }

    @MockitoBean
    private AccessTokenVerifier tokens;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void theTokenSaysWhichMerchant() {
        when(tokens.verify(anyString())).thenAnswer(call -> {
            String token = call.getArgument(0);
            return Mono.just(VerifiedCaller.user(
                    UUID.randomUUID().toString(), token.substring("merchant-".length()), List.of("ADMIN")));
        });
    }

    @Test
    void aMerchantOverItsAllowanceIsToldToWaitInThePlatformsOwnShape() throws Exception {
        String merchant = UUID.randomUUID().toString();

        List<HttpResponse<String>> answers = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            answers.add(send(merchant));
        }

        long allowed = answers.stream().filter(answer -> answer.statusCode() == 200).count();
        assertThat(allowed)
                .as("the burst, and a little of what refilled while sending")
                .isBetween(10L, 20L);

        HttpResponse<String> refused = answers.stream()
                .filter(answer -> answer.statusCode() == 429)
                .findFirst()
                .orElseThrow();
        assertThat(refused.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(refused.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(refused.body())
                .contains("\"code\":\"RATE_LIMITED\"")
                .contains("requests a second");
    }

    @Test
    void theAllowanceComesBackAfterWaiting() throws Exception {
        String merchant = UUID.randomUUID().toString();
        int refused = 0;
        for (int i = 0; i < 30; i++) {
            if (send(merchant).statusCode() == 429) {
                refused++;
            }
        }
        assertThat(refused).isPositive();

        Thread.sleep(1_200);
        assertThat(send(merchant).statusCode())
                .as("after Retry-After, the merchant is served again")
                .isEqualTo(200);
    }

    @Test
    @Timeout(60)
    void oneMerchantsFloodDoesNotStarveAnother() throws Exception {
        String noisy = UUID.randomUUID().toString();
        String quiet = UUID.randomUUID().toString();

        // The quiet merchant alone first, so its latency under the flood has something to be
        // compared with.
        List<Long> alone = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            alone.add(timed(quiet));
            Thread.sleep(250);
        }

        AtomicBoolean flooding = new AtomicBoolean(true);
        AtomicInteger noisyAllowed = new AtomicInteger();
        AtomicInteger noisyRefused = new AtomicInteger();
        List<Integer> quietStatuses = Collections.synchronizedList(new ArrayList<>());
        List<Long> duringTheFlood = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService flood = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int worker = 0; worker < 16; worker++) {
                flood.submit(() -> {
                    while (flooding.get()) {
                        int status = send(noisy).statusCode();
                        (status == 200 ? noisyAllowed : noisyRefused).incrementAndGet();
                    }
                    return null;
                });
            }

            // Three a second for four seconds: under its own allowance of five, all the way through.
            for (int i = 0; i < 12; i++) {
                long started = System.nanoTime();
                HttpResponse<String> answer = send(quiet);
                duringTheFlood.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
                quietStatuses.add(answer.statusCode());
                Thread.sleep(330);
            }
            flooding.set(false);
        }

        assertThat(noisyRefused.get())
                .as("the flood was refused, or this test proved nothing")
                .isGreaterThan(noisyAllowed.get());
        assertThat(quietStatuses)
                .as("the quiet merchant was never refused while the other flooded")
                .containsOnly(200);

        long slowestAlone = Collections.max(alone);
        long slowestDuring = Collections.max(duringTheFlood);
        assertThat(slowestDuring)
                .as("the quiet merchant's slowest request during the flood (alone: %dms, during: %s)",
                        slowestAlone, duringTheFlood)
                .isLessThan(Math.max(500, slowestAlone * 5));
    }

    private long timed(String merchant) throws Exception {
        long started = System.nanoTime();
        assertThat(send(merchant).statusCode()).isEqualTo(200);
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private HttpResponse<String> send(String merchant) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/merchants/" + merchant + "/payments"))
                .header("Authorization", "Bearer merchant-" + merchant)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
