package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a slow or broken acquirer is allowed to cost. ADR 0052.
 *
 * <p>The acquirer is a real HTTP server controlled by the test, because every case here is about
 * what somebody else's system does: failing, hanging, refusing. The assertions are about what
 * this service does in return — how many requests it actually sends, how fast it answers when
 * it does not send one, and whether a merchant merely reading a payment notices any of it.
 */
@SpringBootTest(properties = {
    "mizan.acquirer.timeout=1s",
    "mizan.acquirer.failures-before-giving-up=3",
    // Far longer than any loop below takes. At 400ms, ten authorizations on a machine busy with
    // the full build outlasted it: the breaker let its one trial call through mid-loop, which met
    // the still-broken acquirer, and the test saw a call it had asserted would not be sent.
    "mizan.acquirer.leave-alone-for=" + AcquirerUnderPressureTest.QUIET_MILLIS + "ms",
    "mizan.acquirer.max-concurrent-calls=2",
    "mizan.acquirer.wait-for-a-turn=50ms",
    // Nothing listens here, so risk is unavailable at once and the flow carries on unscored
    // (ADR 0033). What is under test is the acquirer, not the scorer.
    "mizan.risk.base-url=http://127.0.0.1:1"
})
class AcquirerUnderPressureTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** How long the breaker leaves the acquirer alone. A compile-time constant, for the annotation. */
    static final int QUIET_MILLIS = 2000;

    /** An acquirer that approves, fails, hangs or refuses, as the test tells it to. */
    static class FakeAcquirer {

        private final HttpServer server;
        final AtomicInteger authorizationsSent = new AtomicInteger();
        final AtomicInteger capturesSent = new AtomicInteger();
        final AtomicInteger waiting = new AtomicInteger();
        volatile boolean broken;
        volatile boolean refusesCaptures;
        volatile CountDownLatch hangUntil = new CountDownLatch(0);

        FakeAcquirer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/acquirer/authorizations", this::handle);
            server.setExecutor(Executors.newFixedThreadPool(16));
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            if (path.endsWith("/capture")) {
                capturesSent.incrementAndGet();
                if (refusesCaptures) {
                    answer(exchange, 422, """
                            {"type":"about:blank","status":422,
                             "detail":"this authorization has already been voided"}
                            """, "application/problem+json");
                    return;
                }
                answer(exchange, 200, "{}", "application/json");
                return;
            }

            authorizationsSent.incrementAndGet();
            waiting.incrementAndGet();
            try {
                hangUntil.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                waiting.decrementAndGet();
            }
            if (broken) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }

            String requestId = JSON.readTree(request).path("requestId").asString();
            answer(exchange, 200, """
                    {"acquirerReference":"acq-%s","requestId":"%s","outcome":"APPROVED",
                     "reason":null,"amount":125000,"currency":"TRY","cardLastFour":"0000",
                     "decidedAt":"%s","state":"AUTHORIZED"}
                    """.formatted(UUID.randomUUID(), requestId, Instant.now()), "application/json");
        }

        private static void answer(HttpExchange exchange, int status, String body, String type)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            hangUntil.countDown();
            server.stop(0);
        }
    }

    private static FakeAcquirer acquirer;

    @BeforeAll
    static void startTheAcquirer() throws IOException {
        acquirer = new FakeAcquirer();
    }

    @AfterAll
    static void stopTheAcquirer() {
        acquirer.stop();
    }

    @DynamicPropertySource
    static void pointAtIt(DynamicPropertyRegistry registry) {
        registry.add("mizan.acquirer.base-url", () -> acquirer.url());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void mendTheAcquirer() throws Exception {
        acquirer.hangUntil.countDown();
        acquirer.broken = false;
        acquirer.refusesCaptures = false;

        // The breaker keeps state between tests. Waiting out its quiet period and then succeeding
        // once closes it and clears the count, whichever test left it how.
        Thread.sleep(QUIET_MILLIS + 100);
        Merchant merchant = merchant();
        authorize(merchant, created(merchant)).andExpect(status().isOk());

        acquirer.authorizationsSent.set(0);
        acquirer.capturesSent.set(0);
    }

    @Test
    void aBrokenAcquirerIsLeftAloneAfterAFewFailures() throws Exception {
        Merchant merchant = merchant();
        acquirer.broken = true;

        for (int i = 0; i < 3; i++) {
            authorize(merchant, created(merchant)).andExpect(status().isServiceUnavailable());
        }
        assertThat(acquirer.authorizationsSent).hasValue(3);
        assertThat(breakerOpen()).isEqualTo(1.0);

        for (int i = 0; i < 10; i++) {
            authorize(merchant, created(merchant))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("Nothing was sent")));
        }
        assertThat(acquirer.authorizationsSent)
                .as("ten more payments cost the acquirer nothing while it was down")
                .hasValue(3);
        assertThat(notSent("breaker_open")).isGreaterThanOrEqualTo(10);
    }

    @Test
    void aSlowAcquirerCostsAFewTimeoutsNotOnePerPayment() throws Exception {
        Merchant merchant = merchant();
        acquirer.hangUntil = new CountDownLatch(1);

        for (int i = 0; i < 3; i++) {
            authorize(merchant, created(merchant)).andExpect(status().isGatewayTimeout());
        }

        UUID notSent = created(merchant);
        long started = System.nanoTime();
        authorize(merchant, notSent)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value(
                        org.hamcrest.Matchers.containsString("upstream-unavailable")));
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("refused without waiting out another timeout")
                .isLessThan(Duration.ofMillis(900));
        assertThat(acquirer.authorizationsSent).hasValue(3);

        // Not sent means not unknown. A timeout starts a resolution because the money may be
        // reserved; this payment was never offered to the acquirer, so it is simply unauthorized.
        statusOf(merchant, notSent)
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void itIsAskedAgainOnceItHasBeenLeftAloneLongEnough() throws Exception {
        Merchant merchant = merchant();
        acquirer.broken = true;
        for (int i = 0; i < 3; i++) {
            authorize(merchant, created(merchant)).andExpect(status().isServiceUnavailable());
        }
        assertThat(breakerOpen()).isEqualTo(1.0);

        acquirer.broken = false;
        Thread.sleep(QUIET_MILLIS + 100);

        authorize(merchant, created(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        assertThat(breakerOpen()).isEqualTo(0.0);
    }

    @Test
    void aRefusalIsAnAnswerAndNeverOpensTheBreaker() throws Exception {
        Merchant merchant = merchant();
        acquirer.refusesCaptures = true;

        for (int i = 0; i < 6; i++) {
            UUID payment = created(merchant);
            authorize(merchant, payment).andExpect(status().isOk());
            mockMvc.perform(post(payments(merchant) + "/" + payment + "/capture")
                            .with(merchant.writer())
                            .with(Idempotently.freshKey()))
                    .andExpect(status().is(422));
        }
        assertThat(acquirer.capturesSent)
                .as("twice the failure threshold, and every one was still sent")
                .hasValue(6);
        assertThat(breakerOpen()).isEqualTo(0.0);
    }

    @Test
    @Timeout(30)
    void callsBeyondTheLimitAreNotSentAndReadingAPaymentStillAnswers() throws Exception {
        Merchant merchant = merchant();
        UUID readable = created(merchant);
        List<UUID> waiting = List.of(created(merchant), created(merchant));
        UUID beyond = created(merchant);
        acquirer.hangUntil = new CountDownLatch(1);

        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            List<Future<MvcResult>> slow = new ArrayList<>();
            for (UUID payment : waiting) {
                slow.add(callers.submit(() -> authorize(merchant, payment).andReturn()));
            }
            awaitWaiting(2);

            long started = System.nanoTime();
            authorize(merchant, beyond)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("already waiting")));
            statusOf(merchant, readable).andExpect(status().isOk());
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("the third was refused and the read answered while two calls hung")
                    .isLessThan(Duration.ofMillis(900));
            assertThat(acquirer.authorizationsSent).as("the third was never sent").hasValue(2);
            assertThat(notSent("too_many_waiting")).isGreaterThanOrEqualTo(1);

            acquirer.hangUntil.countDown();
            for (Future<MvcResult> call : slow) {
                call.get(10, TimeUnit.SECONDS);
            }
        }
    }

    private void awaitWaiting(int calls) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (acquirer.waiting.get() < calls && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(acquirer.waiting).hasValue(calls);
    }

    private double breakerOpen() {
        return meters.get("mizan.acquirer.breaker.open").gauge().value();
    }

    private double notSent(String because) {
        return meters.get("mizan.acquirer.calls.not.sent").tag("because", because).counter().count();
    }

    private ResultActions authorize(Merchant merchant, UUID payment) throws Exception {
        return mockMvc.perform(post(payments(merchant) + "/" + payment + "/authorize")
                .with(merchant.writer())
                .with(Idempotently.freshKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"card\":\"4000000000000000\"}"));
    }

    private ResultActions statusOf(Merchant merchant, UUID payment) throws Exception {
        return mockMvc.perform(get(payments(merchant) + "/" + payment).with(merchant.writer()));
    }

    private UUID created(Merchant merchant) throws Exception {
        String body = mockMvc.perform(post(payments(merchant))
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":125000,"currency":"TRY","reference":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JSON.readTree(body).path("id").asString());
    }

    private record Merchant(UUID id, UUID userId) {

        RequestPostProcessor writer() {
            return Callers.as(userId, id, Role.ADMIN);
        }
    }

    private static Merchant merchant() {
        return new Merchant(UUID.randomUUID(), UUID.randomUUID());
    }

    private static String payments(Merchant merchant) {
        return "/api/v1/merchants/" + merchant.id + "/payments";
    }
}
