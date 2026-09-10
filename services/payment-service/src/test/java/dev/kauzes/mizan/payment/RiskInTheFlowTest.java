package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
 * Risk in front of an authorization, and what happens when it is not there.
 *
 * <p>The most consequential thing done to the payment flow since it was written, so the tests
 * that matter are the ones about the dependency rather than about the scoring: that a block
 * never reaches the acquirer, that a review charges nobody, and above all that **risk being
 * unreachable does not stop the platform taking money** — in the direction ADR 0033 chose,
 * asserted rather than assumed, so a refactor cannot quietly reverse it.
 *
 * <p>Risk is a real HTTP server here, controlled by the test, because every interesting case is
 * about what somebody else's service does: answering slowly, answering wrongly, not answering.
 */
@SpringBootTest(properties = {
    "mizan.risk.timeout=300ms",
    "mizan.risk.failures-before-giving-up=3",
    "mizan.risk.leave-alone-for=200ms",
    // Driven by hand, so what has happened at each point is a fact rather than a race.
    "mizan.risk.expire-every=3650d"
})
class RiskInTheFlowTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GOOD_CARD = "4000000000000000";
    private static final String SERVICE_TOKEN = "a-service-token-for-tests";

    /** A risk service that says what the test tells it to, when the test lets it. */
    static class FakeRisk {

        private final HttpServer server;
        final AtomicReference<String> verdict = new AtomicReference<>("APPROVE");
        final AtomicInteger asked = new AtomicInteger();
        volatile boolean hang;
        volatile boolean broken;

        FakeRisk() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/risk/scores", this::handle);
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            asked.incrementAndGet();
            exchange.getRequestBody().readAllBytes();

            if (hang) {
                try {
                    Thread.sleep(Duration.ofSeconds(5).toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
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
        risk.verdict.set("APPROVE");
        risk.hang = false;
        risk.broken = false;
        risk.asked.set(0);
        // The breaker keeps state between tests, and its wait is short enough that letting it
        // close is faster than reasoning about which test left it open.
        sleep(250);
    }

    @Test
    void anApprovedPaymentCarriesOnToTheAcquirer() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);

        authorize(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        assertThat(riskVerdictOf(payment)).isEqualTo("APPROVE");
        assertThat(risk.asked).hasValue(1);
    }

    @Test
    void aBlockedPaymentNeverReachesTheAcquirer() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        risk.verdict.set("BLOCK");

        authorize(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineReason")
                        .value(org.hamcrest.Matchers.containsString("was refused")));

        // The whole point of scoring before rather than after: nobody was contacted and no
        // money was reserved.
        assertThat(acquirerReferenceOf(payment))
                .as("no acquirer reference, because the acquirer was never asked")
                .isNull();
        assertThat(riskVerdictOf(payment)).isEqualTo("BLOCK");
    }

    @Test
    void aReviewedPaymentIsHeldAndNobodyIsCharged() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        risk.verdict.set("REVIEW");

        authorize(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HELD_FOR_REVIEW"));

        assertThat(acquirerReferenceOf(payment)).isNull();
        assertThat(riskReasonsOf(payment))
                .as("and it says what the scorer objected to, because a merchant will ask")
                .contains("unusual for this merchant");
        assertThat(heldAtOf(payment)).isNotNull();

        // And the merchant can see it without reading a database. A verdict recorded only in
        // a column is a verdict a merchant has to open a support ticket to find out about.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(payments(merchant) + "/" + payment)
                        .with(merchant.writer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskVerdict").value("REVIEW"))
                .andExpect(jsonPath("$.riskScore").value(55))
                .andExpect(jsonPath("$.riskReasons")
                        .value(org.hamcrest.Matchers.containsString("unusual for this merchant")));
    }

    @Test
    void riskBeingUnreachableDoesNotStopThePlatformTakingMoney() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        risk.broken = true;

        // The choice ADR 0033 made, asserted rather than assumed. Risk is an advisory guard,
        // not a correctness invariant: fraud during an outage is bounded and recoverable, and
        // refusing every payment on the platform is neither.
        authorize(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        assertThat(riskVerdictOf(payment))
                .as("recorded as unscored, so a day of these can be reviewed afterwards")
                .isEqualTo("UNAVAILABLE");
        assertThat(riskReasonsOf(payment)).contains("risk could not be asked");
    }

    @Test
    void andNorDoesRiskBeingSlow() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        risk.hang = true;

        long startedAt = System.nanoTime();
        authorize(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        // A guard that takes as long as the thing it guards has stopped being a guard. The
        // timeout is what makes that true rather than hoped for.
        assertThat(took)
                .as("the customer waited %s, which should be about the risk timeout and not "
                        + "the five seconds risk intended to take", took)
                .isLessThan(Duration.ofSeconds(3));
        assertThat(riskVerdictOf(payment)).isEqualTo("UNAVAILABLE");
    }

    @Test
    @Timeout(120)
    void riskBeingDownCostsAFewTimeoutsRatherThanOnePerPayment() throws Exception {
        Merchant merchant = merchant();
        risk.hang = true;

        // Ten payments while risk hangs. Without a breaker every one of them waits the full
        // timeout, and a platform taking ten payments a second accumulates latency faster than
        // it sheds it — the guard becoming the outage.
        for (int i = 0; i < 10; i++) {
            authorize(merchant, created(merchant)).andExpect(status().isOk());
        }

        assertThat(risk.asked.get())
                .as("the breaker opened after three failures, so risk was asked a handful of "
                        + "times rather than ten")
                .isLessThanOrEqualTo(5);
    }

    @Test
    @Timeout(120)
    void andStartsAskingAgainOnceRiskRecovers() throws Exception {
        Merchant merchant = merchant();
        risk.broken = true;
        for (int i = 0; i < 4; i++) {
            authorize(merchant, created(merchant)).andExpect(status().isOk());
        }

        risk.broken = false;
        sleep(300);
        risk.asked.set(0);

        UUID payment = created(merchant);
        authorize(merchant, payment).andExpect(status().isOk());

        assertThat(risk.asked.get()).as("asked again, rather than written off").isPositive();
        assertThat(riskVerdictOf(payment)).isEqualTo("APPROVE");
    }

    @Test
    void aHeldPaymentThatNobodyRulesOnExpiresRefused() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        risk.verdict.set("REVIEW");
        authorize(merchant, payment).andExpect(status().isOk());

        heldSince(payment, java.time.Instant.now().minus(Duration.ofDays(2)));
        held.expireWhatNobodyRuledOn();

        // Expires to declined, not to authorized. Letting a hold resolve to "take the money"
        // when nobody looked makes the safe-looking answer the default, which turns a review
        // queue into a delay before approving everything.
        assertThat(statusOf(payment)).isEqualTo("DECLINED");
        assertThat(declineReasonOf(payment))
                .as("and says so, because a silent state change is not a decision")
                .contains("expired")
                .contains("Nothing was charged");
        assertThat(acquirerReferenceOf(payment)).isNull();
    }

    @Test
    void aHeldPaymentThatIsStillYoungIsLeftAlone() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        risk.verdict.set("REVIEW");
        authorize(merchant, payment).andExpect(status().isOk());

        held.expireWhatNobodyRuledOn();

        assertThat(statusOf(payment))
                .as("an analyst has not had a chance yet")
                .isEqualTo("HELD_FOR_REVIEW");
    }

    @Test
    void aHeldPaymentThatIsReleasedIsNotScoredAgain() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        risk.verdict.set("REVIEW");
        authorize(merchant, payment).andExpect(status().isOk());

        // Standing in for MIZ-59's analyst release: authorizing a payment that is already
        // held. The scorer must not get a second say — a person has overruled it, and asking
        // again would let it overrule them back, forever.
        risk.asked.set(0);
        authorize(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        assertThat(risk.asked).hasValue(0);
    }

    // -- helpers ---------------------------------------------------------------------------

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

    private String riskVerdictOf(UUID payment) {
        return one("risk_verdict", payment);
    }

    private String riskReasonsOf(UUID payment) {
        return one("risk_reasons", payment);
    }

    private String declineReasonOf(UUID payment) {
        return one("decline_reason", payment);
    }

    private String acquirerReferenceOf(UUID payment) {
        return one("acquirer_reference", payment);
    }

    private Object heldAtOf(UUID payment) {
        return jdbc.queryForObject(
                "select held_at from payment where id = ?", Object.class, payment);
    }

    private String one(String column, UUID payment) {
        return jdbc.queryForObject(
                "select " + column + " from payment where id = ?", String.class, payment);
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

    private static String urlOf(ConfigurableApplicationContext service) {
        return "http://localhost:" + service.getEnvironment().getProperty("local.server.port");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
