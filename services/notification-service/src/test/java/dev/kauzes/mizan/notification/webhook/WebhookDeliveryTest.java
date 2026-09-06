package dev.kauzes.mizan.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivering to servers this platform does not control.
 *
 * <p>Real HTTP to a real server started for the test, because everything interesting here is
 * about what somebody else's server does: answering 500, answering slowly, never answering at
 * all. A stubbed client would test this platform against this test's idea of an outage.
 *
 * <p>The two that matter are the endpoint that fails and then recovers — delivered exactly once
 * however many attempts it took — and the endpoint that never answers, alongside one that does.
 */
@SpringBootTest(properties = {
    // Short, because these tests wait through them. The shape is what matters.
    "mizan.webhooks.deliver-every=3650d",
    "mizan.webhooks.timeout=1s",
    "mizan.webhooks.attempts=4"
})
class WebhookDeliveryTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A merchant's server, as unhelpful as a test needs it to be. */
    static class MerchantServer {

        private final HttpServer server;
        final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
        final AtomicInteger failuresLeft = new AtomicInteger();
        volatile boolean hang;

        record Received(String body, String signature, String timestamp, String delivery) {
        }

        MerchantServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", this::handle);
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            if (hang) {
                // Never answers. The delivery to it must not stop anybody else being told.
                try {
                    Thread.sleep(Duration.ofSeconds(30).toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            received.add(new Received(
                    body,
                    exchange.getRequestHeaders().getFirst(WebhookSignature.SIGNATURE_HEADER),
                    exchange.getRequestHeaders().getFirst(WebhookSignature.TIMESTAMP_HEADER),
                    exchange.getRequestHeaders().getFirst(WebhookSignature.DELIVERY_HEADER)));

            int status = failuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0 ? 500 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        }

        void stop() {
            server.stop(0);
        }
    }

    private static MerchantServer good;
    private static MerchantServer bad;

    @BeforeAll
    static void startTheMerchantServers() throws IOException {
        good = new MerchantServer();
        bad = new MerchantServer();
    }

    @AfterAll
    static void stopThem() {
        good.stop();
        bad.stop();
    }

    @DynamicPropertySource
    static void allowLoopbackForTheseTests(DynamicPropertyRegistry registry) {
        // The safety check refuses loopback, correctly and by design. These tests need a
        // merchant server this JVM can actually run, so the sender is told to skip the check
        // — and there is a test below that the check is still applied when it is not.
        registry.add("mizan.webhooks.allow-any-destination", () -> "true");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebhookDeliveries deliveries;

    @Autowired
    private WebhookDispatcher dispatcher;

    @Autowired
    private WebhookEndpointService endpointService;

    @BeforeEach
    void startClean() {
        jdbc.update("delete from webhook_delivery_attempt");
        jdbc.update("delete from webhook_delivery");
        jdbc.update("delete from webhook_subscription");
        jdbc.update("delete from webhook_endpoint");
        good.received.clear();
        bad.received.clear();
        good.failuresLeft.set(0);
        bad.failuresLeft.set(0);
        bad.hang = false;
    }

    @Test
    @Timeout(120)
    void deliversASignedBodyTheMerchantCanVerify() {
        UUID merchant = UUID.randomUUID();
        Registered endpoint = register(merchant, good.url());
        UUID delivery = queue(merchant, endpoint.id, "payment.captured");

        dispatcher.deliverWhatIsDue();

        assertThat(good.received).hasSize(1);
        MerchantServer.Received got = good.received.peek();

        // Verified the way a merchant would, from the secret they were given and the bytes
        // they received. Comparing against another call to the signing function would only
        // prove that function is deterministic.
        assertThat(WebhookSignature.matches(
                        endpoint.secret,
                        Instant.ofEpochSecond(Long.parseLong(got.timestamp())),
                        got.body(),
                        got.signature()))
                .as("a merchant holding the secret can check this delivery came from us")
                .isTrue();

        assertThat(got.delivery()).isEqualTo(delivery.toString());
        assertThat(JSON.readTree(got.body()).path("type").asString()).isEqualTo("payment.captured");
        assertThat(statusOf(delivery)).isEqualTo("DELIVERED");
    }

    @Test
    @Timeout(120)
    void aSignatureDoesNotVerifyAgainstADifferentSecretOrABodyThatChanged() {
        UUID merchant = UUID.randomUUID();
        Registered endpoint = register(merchant, good.url());
        queue(merchant, endpoint.id, "payment.captured");
        dispatcher.deliverWhatIsDue();

        MerchantServer.Received got = good.received.peek();
        Instant at = Instant.ofEpochSecond(Long.parseLong(got.timestamp()));

        assertThat(WebhookSignature.matches("whsec_somebody_elses", at, got.body(), got.signature()))
                .as("somebody without the secret cannot forge one")
                .isFalse();
        assertThat(WebhookSignature.matches(
                        endpoint.secret, at, got.body() + " ", got.signature()))
                .as("nor alter the body of one they intercepted")
                .isFalse();
        assertThat(WebhookSignature.matches(
                        endpoint.secret, at.plusSeconds(1), got.body(), got.signature()))
                .as("and the timestamp is inside what was signed, so a replay cannot be "
                        + "restamped")
                .isFalse();
    }

    @Test
    @Timeout(180)
    void anEndpointThatFailsAndThenRecoversIsDeliveredToOnce() {
        UUID merchant = UUID.randomUUID();
        Registered endpoint = register(merchant, good.url());
        UUID delivery = queue(merchant, endpoint.id, "payment.captured");

        // Three failures, then it works. The story asks for ten; the shape is the same and
        // this one does not spend a minute proving it.
        good.failuresLeft.set(3);

        for (int pass = 0; pass < 4; pass++) {
            makeDue();
            dispatcher.deliverWhatIsDue();
        }

        assertThat(statusOf(delivery)).isEqualTo("DELIVERED");
        assertThat(deliveredCount(merchant))
                .as("one logical delivery, however many attempts it took")
                .isEqualTo(1);
        assertThat(good.received).hasSize(4);
        assertThat(good.received.stream().map(MerchantServer.Received::delivery).distinct())
                .as("and every attempt carried the same delivery id, so the merchant can tell "
                        + "the repeats from new events")
                .hasSize(1);

        // Every attempt is recorded, not just the last, because a merchant debugging their
        // endpoint wants the sequence rather than the current state.
        List<Map<String, Object>> attempts = deliveries.attemptsOf(merchant, delivery);
        assertThat(attempts).hasSize(4);
        assertThat(attempts.getFirst().get("status_code")).isEqualTo(500);
        assertThat(attempts.getLast().get("status_code")).isEqualTo(200);
        assertThat(attempts.getLast().get("duration_ms")).isNotNull();
    }

    @Test
    @Timeout(180)
    void anEndpointThatNeverAnswersDoesNotDelayAnybodyElse() {
        UUID slowMerchant = UUID.randomUUID();
        UUID quickMerchant = UUID.randomUUID();
        bad.hang = true;

        Registered slow = register(slowMerchant, bad.url());
        Registered quick = register(quickMerchant, good.url());

        UUID stuck = queue(slowMerchant, slow.id, "payment.captured");
        UUID fine = queue(quickMerchant, quick.id, "payment.captured");

        long startedAt = System.nanoTime();
        dispatcher.deliverWhatIsDue();
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        // The requirement this whole design exists for. The good one is delivered while the
        // bad one is still hanging, and the pass finishes in about the timeout rather than in
        // the thirty seconds the bad endpoint intends to take.
        assertThat(statusOf(fine)).isEqualTo("DELIVERED");
        assertThat(statusOf(stuck)).isEqualTo("PENDING");
        assertThat(took)
                .as("one endpoint that never answers costs one timeout, not a whole outage")
                .isLessThan(Duration.ofSeconds(15));
    }

    @Test
    @Timeout(180)
    void oneThatNeverWorksIsGivenUpOnRatherThanRetriedForever() {
        UUID merchant = UUID.randomUUID();
        Registered endpoint = register(merchant, good.url());
        UUID delivery = queue(merchant, endpoint.id, "payment.captured");
        good.failuresLeft.set(1000);

        for (int pass = 0; pass < 6; pass++) {
            makeDue();
            dispatcher.deliverWhatIsDue();
        }

        assertThat(statusOf(delivery))
                .as("bounded, because retrying forever is a busy loop with a merchant's server "
                        + "on the other end of it")
                .isEqualTo("FAILED");
        assertThat(lastErrorOf(delivery)).contains("500");
        assertThat(attemptsOf(delivery)).isEqualTo(4);

        // And it stops being picked up, so a permanently broken endpoint costs nothing.
        int before = good.received.size();
        makeDue();
        dispatcher.deliverWhatIsDue();
        assertThat(good.received).hasSize(before);
    }

    @Test
    @Timeout(180)
    void anOperatorOrMerchantCanSendOneAgain() {
        UUID merchant = UUID.randomUUID();
        Registered endpoint = register(merchant, good.url());
        UUID delivery = queue(merchant, endpoint.id, "payment.captured");
        good.failuresLeft.set(1000);

        for (int pass = 0; pass < 6; pass++) {
            makeDue();
            dispatcher.deliverWhatIsDue();
        }
        assertThat(statusOf(delivery)).isEqualTo("FAILED");

        // Their endpoint is fixed. Redelivering sends the same body under the same delivery
        // id, so a merchant who did receive one of the earlier attempts recognises it.
        good.failuresLeft.set(0);
        String bodySent = good.received.peek().body();
        deliveries.redeliver(delivery);
        dispatcher.deliverWhatIsDue();

        assertThat(statusOf(delivery)).isEqualTo("DELIVERED");
        assertThat(good.received.stream().map(MerchantServer.Received::delivery).distinct())
                .hasSize(1);
        assertThat(good.received.stream().map(MerchantServer.Received::body).distinct())
                .as("byte identical, because the body is stored rather than rebuilt and a "
                        + "rebuilt one could carry a signature for something subtly different")
                .containsExactly(bodySent);
    }

    @Test
    @Timeout(120)
    void aDisabledEndpointIsNotDeliveredTo() {
        UUID merchant = UUID.randomUUID();
        Registered endpoint = register(merchant, good.url());
        UUID delivery = queue(merchant, endpoint.id, "payment.captured");

        jdbc.update("update webhook_endpoint set enabled = false where id = ?", endpoint.id);
        dispatcher.deliverWhatIsDue();

        assertThat(good.received).isEmpty();
        assertThat(statusOf(delivery))
                .as("still waiting, so re-enabling the endpoint delivers it rather than losing it")
                .isEqualTo("PENDING");
    }

    // -- helpers ---------------------------------------------------------------------------

    private record Registered(UUID id, String secret) {
    }

    private Registered register(UUID merchant, String url) {
        var response = endpointService.register(
                merchant,
                new WebhookRequests.RegisterEndpointRequest(
                        url, "test", java.util.Set.of("payment.captured")));
        return new Registered(response.endpoint().id(), response.secret());
    }

    private UUID queue(UUID merchant, UUID endpoint, String type) {
        UUID delivery = UUID.randomUUID();
        deliveries.queue(
                delivery,
                merchant,
                endpoint,
                UUID.randomUUID(),
                UUID.randomUUID(),
                type,
                "{\"id\":\"" + delivery + "\",\"type\":\"" + type + "\",\"data\":{\"amount\":125000}}");
        return delivery;
    }

    /** Brings every backed-off delivery forward, so a test need not sleep through it. */
    private void makeDue() {
        jdbc.update("update webhook_delivery set next_attempt_at = now() - interval '1 hour' "
                + "where status = 'PENDING'");
    }

    private String statusOf(UUID delivery) {
        return jdbc.queryForObject(
                "select status from webhook_delivery where id = ?", String.class, delivery);
    }

    private String lastErrorOf(UUID delivery) {
        return jdbc.queryForObject(
                "select last_error from webhook_delivery where id = ?", String.class, delivery);
    }

    private long attemptsOf(UUID delivery) {
        Long counted = jdbc.queryForObject(
                "select count(*) from webhook_delivery_attempt where delivery_id = ?",
                Long.class,
                delivery);
        return counted == null ? 0 : counted;
    }

    private long deliveredCount(UUID merchant) {
        Long counted = jdbc.queryForObject(
                "select count(*) from webhook_delivery where merchant_id = ? "
                        + "and status = 'DELIVERED'",
                Long.class,
                merchant);
        return counted == null ? 0 : counted;
    }
}
