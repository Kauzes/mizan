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
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The other half of a hold: somebody looking at it.
 *
 * <p>MIZ-58 gave this platform a way to stop a payment. A stop nobody can lift is a decline
 * with a customer waiting attached, so what matters here is that a person can act on the queue,
 * that only a person can, and that a merchant cannot get past it by simply asking again.
 *
 * <p>That last one is the test worth having. Everything else on this page describes a feature;
 * {@link #aMerchantCannotSkipTheReviewByAuthorizingAgain} describes the hole that the obvious
 * implementation leaves, where being held means nothing to whoever was held.
 */
@SpringBootTest(properties = {
    "mizan.risk.timeout=300ms",
    "mizan.risk.failures-before-giving-up=3",
    "mizan.risk.leave-alone-for=200ms",
    "mizan.risk.expire-every=3650d"
})
class ReviewTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GOOD_CARD = "4000000000000000";
    private static final String SERVICE_TOKEN = "a-service-token-for-tests";

    /** A risk service that holds everything, and remembers what it was told afterwards. */
    static class FakeRisk {

        private final HttpServer server;
        final AtomicReference<String> verdict = new AtomicReference<>("REVIEW");
        final AtomicInteger scored = new AtomicInteger();
        final List<String> toldAbout = new CopyOnWriteArrayList<>();
        volatile boolean broken;

        FakeRisk() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/risk/scores", this::score);
            server.createContext("/api/v1/risk/rulings", this::ruling);
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            server.start();
        }

        private void score(HttpExchange exchange) throws IOException {
            scored.incrementAndGet();
            exchange.getRequestBody().readAllBytes();

            if (broken) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }

            String body = """
                    {"paymentId":"%s","verdict":"%s","score":55,"reviewAbove":40,
                     "blockAbove":70,
                     "signals":[{"rule":"UNUSUAL_AMOUNT","contribution":30,
                                 "because":"the amount is unusual for this merchant"}],
                     "at":"2026-09-10T12:00:00Z"}
                    """.formatted(UUID.randomUUID(), verdict.get());

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private void ruling(HttpExchange exchange) throws IOException {
            String body = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            if (broken) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }

            toldAbout.add(body);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }

    private static FakeRisk risk;
    private static ConfigurableApplicationContext acquirer;
    private static ConfigurableApplicationContext ledger;

    @BeforeAll
    static void startEverything() throws IOException {
        risk = new FakeRisk();

        acquirer = new SpringApplicationBuilder(
                        dev.kauzes.mizan.banksim.BankSimulatorApplication.class)
                .run("--spring.config.name=acquirer-test");

        ledger = new SpringApplicationBuilder(dev.kauzes.mizan.ledger.LedgerApplication.class)
                .run(
                        "--spring.config.name=ledger-test",
                        "--spring.datasource.url=" + MizanContainers.database("ledger"),
                        "--spring.datasource.username=" + MizanContainers.postgres().getUsername(),
                        "--spring.datasource.password=" + MizanContainers.postgres().getPassword(),
                        "--mizan.internal.service-token=" + SERVICE_TOKEN);
    }

    @AfterAll
    static void stopThem() {
        risk.stop();
        if (acquirer != null) {
            acquirer.close();
        }
        if (ledger != null) {
            ledger.close();
        }
    }

    @DynamicPropertySource
    static void pointAtThem(DynamicPropertyRegistry registry) {
        registry.add("mizan.acquirer.base-url", () -> urlOf(acquirer));
        registry.add("mizan.ledger.base-url", () -> urlOf(ledger));
        registry.add("mizan.risk.base-url", () -> risk.url());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private HeldPayments held;

    @BeforeEach
    void mendRisk() {
        risk.verdict.set("REVIEW");
        risk.broken = false;
        risk.scored.set(0);
        risk.toldAbout.clear();
        sleep(250);
    }

    @Test
    void whatWasHeldIsWaitingForSomebodyOldestFirst() throws Exception {
        Merchant merchant = merchant();
        UUID first = heldPayment(merchant);
        UUID second = heldPayment(merchant);

        mockMvc.perform(get(reviews(merchant)).with(merchant.analyst()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(first.toString()))
                .andExpect(jsonPath("$[1].id").value(second.toString()))
                .andExpect(jsonPath("$[0].riskReasons")
                        .value(org.hamcrest.Matchers.containsString("unusual for this merchant")));

        // Somebody else's queue is somebody else's. The tenant boundary is not softer for
        // the people whose job is to look at other people's payments.
        mockMvc.perform(get(reviews(merchant())).with(merchant.analyst()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMerchantCannotSkipTheReviewByAuthorizingAgain() throws Exception {
        Merchant merchant = merchant();
        UUID payment = heldPayment(merchant);

        // The whole hold is worth exactly this much: whether the merchant it was applied to
        // can lift it themselves. Sending the authorization again is the obvious way to try.
        risk.scored.set(0);
        authorize(merchant, payment)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("held for review")));

        assertThat(statusOf(payment)).isEqualTo("HELD_FOR_REVIEW");
        assertThat(acquirerReferenceOf(payment))
                .as("and the acquirer was never asked, which is the point of holding it")
                .isNull();
        assertThat(risk.scored).hasValue(0);
    }

    @Test
    void aReleasedPaymentIsAuthorizedWithoutBeingScoredAgain() throws Exception {
        Merchant merchant = merchant();
        UUID payment = heldPayment(merchant);

        release(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewRuling").value("RELEASED"))
                .andExpect(jsonPath("$.reviewRuledBy").value(merchant.analystId.toString()))
                // Still held: releasing says the scorer was wrong, and charging the customer
                // stays something the merchant does with the card this service does not keep.
                .andExpect(jsonPath("$.status").value("HELD_FOR_REVIEW"));

        risk.scored.set(0);
        authorize(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        // A person overruled the scorer. Asking it again would let it overrule them back,
        // and then nothing an analyst decides means anything.
        assertThat(risk.scored).hasValue(0);
    }

    @Test
    void aRefusedPaymentIsDeclinedAndSaysWho() throws Exception {
        Merchant merchant = merchant();
        UUID payment = heldPayment(merchant);

        refuse(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.reviewRuling").value("REFUSED"));

        assertThat(declineReasonOf(payment)).contains("Refused after review");
        assertThat(acquirerReferenceOf(payment))
                .as("nobody was charged, and now nobody will be")
                .isNull();

        authorize(merchant, payment).andExpect(status().isUnprocessableContent());
    }

    @Test
    void onlyAPersonRules() throws Exception {
        Merchant merchant = merchant();
        UUID payment = heldPayment(merchant);

        // An API key is what a merchant puts in a cron job. A merchant who could put their own
        // review queue in a cron job does not have a review queue, and because these rulings
        // teach the scorer, they would be teaching it to stop holding their payments at all.
        mockMvc.perform(post(reviews(merchant) + "/" + payment + "/release")
                        .with(Callers.apiKey(merchant.analystId, merchant.id, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"why\":\"a script said so\"}"))
                .andExpect(status().isForbidden());

        assertThat(rulingOf(payment)).isNull();
    }

    @Test
    void andOnlyAPersonWhoseJobItIs() throws Exception {
        Merchant merchant = merchant();
        UUID payment = heldPayment(merchant);

        mockMvc.perform(post(reviews(merchant) + "/" + payment + "/release")
                        .with(Callers.as(merchant.analystId, merchant.id, Role.VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"why\":\"looks fine to me\"}"))
                .andExpect(status().isForbidden());

        assertThat(rulingOf(payment)).isNull();
    }

    @Test
    void aPaymentIsRuledOnOnce() throws Exception {
        Merchant merchant = merchant();
        UUID payment = heldPayment(merchant);

        release(merchant, payment).andExpect(status().isOk());

        // Releasing leaves the payment held until the merchant authorizes it, so a queue that
        // asked about the status rather than about the ruling would offer this one again.
        release(merchant, payment)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("already released")));

        mockMvc.perform(get(reviews(merchant)).with(merchant.analyst()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theScorerIsToldWhatWasDecided() throws Exception {
        Merchant merchant = merchant();
        UUID payment = heldPayment(merchant);

        release(merchant, payment).andExpect(status().isOk());

        // The feedback loop, from this end. A ruling the scorer never hears about is a queue
        // that teaches the platform nothing, worked forever.
        assertThat(risk.toldAbout).hasSize(1);
        assertThat(risk.toldAbout.getFirst())
                .contains("\"ruling\":\"RELEASED\"")
                .contains(payment.toString())
                .contains(merchant.analystId.toString());
    }

    @Test
    void butARulingIsNotLostIfTheScorerIsDown() throws Exception {
        Merchant merchant = merchant();
        UUID payment = heldPayment(merchant);
        risk.broken = true;

        // The same judgement as scoring, in the other direction: risk is a guard rather than
        // an invariant, and an analyst should not be unable to release a customer's payment
        // because a scorer is down. One ruling's worth of learning is the smaller loss.
        release(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewRuling").value("RELEASED"));

        assertThat(rulingOf(payment)).isEqualTo("RELEASED");
    }

    @Test
    void aReleasedPaymentIsNotExpiredOutFromUnderTheAnalyst() throws Exception {
        Merchant merchant = merchant();
        UUID payment = heldPayment(merchant);
        release(merchant, payment).andExpect(status().isOk());

        heldSince(payment, java.time.Instant.now().minus(Duration.ofDays(2)));
        held.expireWhatNobodyRuledOn();

        // A person decided. Expiring it would overrule them by doing nothing, which is the
        // worst way to be overruled: no decision, no record, and a declined customer.
        assertThat(statusOf(payment)).isEqualTo("HELD_FOR_REVIEW");
        assertThat(declineReasonOf(payment)).isNull();
    }

    // -- helpers ---------------------------------------------------------------------------

    private ResultActions release(Merchant merchant, UUID payment) throws Exception {
        return mockMvc.perform(post(reviews(merchant) + "/" + payment + "/release")
                .with(merchant.analyst())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"why\":\"known customer, they called to confirm\"}"));
    }

    private ResultActions refuse(Merchant merchant, UUID payment) throws Exception {
        return mockMvc.perform(post(reviews(merchant) + "/" + payment + "/refuse")
                .with(merchant.analyst())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"why\":\"the card is one we have seen before\"}"));
    }

    private UUID heldPayment(Merchant merchant) throws Exception {
        UUID payment = created(merchant);
        authorize(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HELD_FOR_REVIEW"));
        return payment;
    }

    private ResultActions authorize(Merchant merchant, UUID payment) throws Exception {
        return mockMvc.perform(post(payments(merchant) + "/" + payment + "/authorize")
                .with(merchant.writer())
                .with(Idempotently.freshKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"card\":\"" + GOOD_CARD + "\"}"));
    }

    private UUID created(Merchant merchant) throws Exception {
        String body = mockMvc.perform(post(payments(merchant))
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":125000,"currency":"TRY","reference":"order-%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        return UUID.fromString(JSON.readTree(body).path("id").asString());
    }

    private void heldSince(UUID payment, java.time.Instant when) {
        jdbc.update(
                "update payment set held_at = ? where id = ?",
                java.sql.Timestamp.from(when),
                payment);
    }

    private String statusOf(UUID payment) {
        return one("status", payment);
    }

    private String rulingOf(UUID payment) {
        return one("review_ruling", payment);
    }

    private String declineReasonOf(UUID payment) {
        return one("decline_reason", payment);
    }

    private String acquirerReferenceOf(UUID payment) {
        return one("acquirer_reference", payment);
    }

    private String one(String column, UUID payment) {
        return jdbc.queryForObject(
                "select " + column + " from payment where id = ?", String.class, payment);
    }

    private record Merchant(UUID id, UUID userId, UUID analystId) {

        RequestPostProcessor writer() {
            return Callers.as(userId, id, Role.ADMIN);
        }

        RequestPostProcessor analyst() {
            return Callers.as(analystId, id, Role.ANALYST);
        }
    }

    private static Merchant merchant() {
        return new Merchant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static String payments(Merchant merchant) {
        return "/api/v1/merchants/" + merchant.id + "/payments";
    }

    private static String reviews(Merchant merchant) {
        return "/api/v1/merchants/" + merchant.id + "/reviews";
    }

    private static String urlOf(ConfigurableApplicationContext service) {
        return "http://localhost:" + service.getEnvironment().getProperty("local.server.port");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
