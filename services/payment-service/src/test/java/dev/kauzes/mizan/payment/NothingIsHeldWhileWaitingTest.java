package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * A database connection is for talking to Postgres, not for holding a place in a queue.
 *
 * <p>Authorizing used to run inside one transaction that spanned the calls to risk and to the acquirer,
 * so every request held a connection for as long as somebody else took to answer. At thirty payments a
 * second that was 47 request threads queued for a pool of ten, and it was the platform whole latency
 * tail (MIZ-104, docs/performance).
 *
 * <p>This holds the acquirer still, mid-authorization, and asks the pool what it is doing. The answer
 * has to be nothing. The other half of the change is what replaced the long transaction: a mark on the
 * payment that stops two authorizations of one payment being out at once, which is checked here too.
 */
@SpringBootTest(properties = {
    "mizan.acquirer.timeout=30s",
    // Nothing listens here, so risk is unavailable at once and the flow carries on unscored (ADR
    // 0033). What is under test is what the pool is doing while the acquirer thinks.
    "mizan.risk.base-url=http://127.0.0.1:1"
})
class NothingIsHeldWhileWaitingTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** An acquirer that can be held still, so the pool can be asked what it is doing meanwhile. */
    static class HoldableAcquirer {

        private final HttpServer server;
        final AtomicInteger authorizationsSent = new AtomicInteger();
        final AtomicInteger waiting = new AtomicInteger();
        volatile CountDownLatch hangUntil = new CountDownLatch(0);

        HoldableAcquirer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/acquirer/authorizations", this::handle);
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            authorizationsSent.incrementAndGet();
            waiting.incrementAndGet();
            try {
                hangUntil.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                waiting.decrementAndGet();
            }

            String requestId = JSON.readTree(request).path("requestId").asString();
            byte[] body = ("""
                    {"acquirerReference":"acq-%s","requestId":"%s","outcome":"APPROVED",
                     "reason":null,"amount":125000,"currency":"TRY","cardLastFour":"0000",
                     "decidedAt":"%s","state":"AUTHORIZED"}
                    """.formatted(UUID.randomUUID(), requestId, Instant.now()))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }

    private static HoldableAcquirer acquirer;

    @BeforeAll
    static void startTheAcquirer() throws IOException {
        acquirer = new HoldableAcquirer();
    }

    @AfterAll
    static void stopTheAcquirer() {
        acquirer.stop();
    }

    @DynamicPropertySource
    static void useIt(DynamicPropertyRegistry registry) {
        registry.add("mizan.acquirer.base-url", acquirer::url);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Timeout(120)
    void noDatabaseConnectionIsHeldWhileTheAcquirerIsThinking() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        acquirer.hangUntil = new CountDownLatch(1);

        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            Future<?> authorizing = thread.submit(() -> authorize(merchant, payment));
            waitUntilTheAcquirerHasIt();

            assertThat(activeConnections())
                    .as("the request is waiting on somebody else network, and is holding no "
                            + "connection while it does: that queue was the platform latency tail")
                    .isZero();

            acquirer.hangUntil.countDown();
            authorizing.get(60, TimeUnit.SECONDS);
        } finally {
            acquirer.hangUntil.countDown();
            thread.shutdownNow();
        }

        statusOf(merchant, payment).andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    @Timeout(120)
    void andASecondAuthorizationOfThatPaymentIsRefusedRatherThanSent() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        acquirer.hangUntil = new CountDownLatch(1);
        int sentBefore = acquirer.authorizationsSent.get();

        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            Future<?> authorizing = thread.submit(() -> authorize(merchant, payment));
            waitUntilTheAcquirerHasIt();

            // What the long transaction used to do by making the second request wait for the first.
            // Now the payment itself says an authorization is out, and says so to anybody who asks.
            authorize(merchant, payment)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONTENDED"))
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("already being authorized")));

            acquirer.hangUntil.countDown();
            authorizing.get(60, TimeUnit.SECONDS);
        } finally {
            acquirer.hangUntil.countDown();
            thread.shutdownNow();
        }

        assertThat(acquirer.authorizationsSent.get() - sentBefore)
                .as("one payment, one authorization: the acquirer was not asked twice")
                .isEqualTo(1);
        statusOf(merchant, payment).andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    @Timeout(120)
    void andTheMarkDoesNotOutliveTheAttemptThatWroteIt() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        acquirer.hangUntil = new CountDownLatch(0);

        authorize(merchant, payment).andExpect(status().isOk());

        // Authorized, so the mark is gone — and a payment that still said one was in flight would be
        // a payment nobody could ever authorize again.
        statusOf(merchant, payment)
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        assertThat(theMarkOn(payment))
                .as("the mark is cleared by the transition that records the answer")
                .isNull();
    }

    private void waitUntilTheAcquirerHasIt() throws InterruptedException {
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (acquirer.waiting.get() == 0 && System.nanoTime() < giveUpAt) {
            Thread.sleep(20);
        }
        assertThat(acquirer.waiting.get()).as("the acquirer never received the authorization").isPositive();
        // The request is inside the acquirer call; give the pool a moment to settle so this is not
        // reading it mid-commit of the step before.
        Thread.sleep(250);
    }

    private int activeConnections() {
        return ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();
    }

    private Object theMarkOn(UUID payment) {
        return jdbc.queryForObject(
                "select authorization_started_at from payment where id = ?", Object.class, payment);
    }

    private ResultActions authorize(Merchant merchant, UUID payment) {
        try {
            return mockMvc.perform(post(payments(merchant) + "/" + payment + "/authorize")
                    .with(merchant.writer())
                    .with(Idempotently.freshKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"card\":\"4000000000000000\"}"));
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
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
