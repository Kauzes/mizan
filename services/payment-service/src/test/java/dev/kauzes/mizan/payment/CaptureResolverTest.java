package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * A capture stopped between the acquirer and the ledger is found and finished. MIZ-90.
 *
 * <p>Before this, the payment was left authorized with nothing on it to say a capture had begun.
 * The money was taken and not recorded, and only the merchant sending the capture again would ever
 * finish it. Nothing here retries a capture on the merchant's behalf: every test sends it once and
 * lets the sweep do the rest, which is the case that used to lose money.
 *
 * <p>The acquirer and the ledger are real, and wrapped in things this test can break at a chosen
 * moment, as in the refund saga. The sweep is off and driven by hand, so what has happened at each
 * point is a fact rather than a race.
 */
@SpringBootTest(properties = {
    "mizan.captures.resolve-every=3650d",
    "mizan.captures.resolve-after=0s"
})
@Import(CaptureResolverTest.WithInterruptibleDependencies.class)
class CaptureResolverTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GOOD_CARD = "4000000000000000";
    private static final String SERVICE_TOKEN = "a-service-token-for-tests";

    private static ConfigurableApplicationContext acquirerService;
    private static ConfigurableApplicationContext ledgerService;

    /** A ledger that can be told to stop recording captures. */
    static class BreakableLedger extends LedgerClient {

        final AtomicReference<RuntimeException> broken = new AtomicReference<>();

        BreakableLedger(
                RestClient.Builder builder,
                String baseUrl,
                java.time.Duration timeout,
                String serviceToken) {
            super(builder, baseUrl, timeout, serviceToken);
        }

        @Override
        public UUID recordCapture(UUID merchantId, Payment payment) {
            RuntimeException failure = broken.get();
            if (failure != null) {
                throw failure;
            }
            return super.recordCapture(merchantId, payment);
        }
    }

    /**
     * An acquirer that can fail a capture before sending it, or take the money and then lose the
     * answer, which are the two failures a caller cannot tell apart.
     */
    static class BreakableAcquirer extends AcquirerClient {

        /** Thrown instead of sending: the capture never reached the acquirer. */
        final AtomicReference<RuntimeException> failsBeforeSending = new AtomicReference<>();

        /** Thrown after sending: the acquirer took the money and the answer was lost. */
        final AtomicReference<RuntimeException> losesTheAnswer = new AtomicReference<>();

        BreakableAcquirer(
                RestClient.Builder builder,
                io.micrometer.core.instrument.MeterRegistry meters,
                String baseUrl,
                java.time.Duration timeout) {
            super(builder, meters, baseUrl, timeout,
                    10, java.time.Duration.ofSeconds(10), 8, java.time.Duration.ofMillis(100));
        }

        @Override
        public void capture(String acquirerReference) {
            RuntimeException before = failsBeforeSending.get();
            if (before != null) {
                throw before;
            }
            super.capture(acquirerReference);
            RuntimeException after = losesTheAnswer.get();
            if (after != null) {
                throw after;
            }
        }
    }

    @TestConfiguration
    static class WithInterruptibleDependencies {

        @Bean
        @Primary
        BreakableLedger breakableLedger(
                RestClient.Builder builder,
                @org.springframework.beans.factory.annotation.Value("${mizan.ledger.base-url}")
                        String baseUrl,
                @org.springframework.beans.factory.annotation.Value("${mizan.internal.service-token}")
                        String serviceToken) {

            return new BreakableLedger(builder, baseUrl, java.time.Duration.ofSeconds(5), serviceToken);
        }

        @Bean
        @Primary
        BreakableAcquirer breakableAcquirer(
                RestClient.Builder builder,
                io.micrometer.core.instrument.MeterRegistry meters,
                @org.springframework.beans.factory.annotation.Value("${mizan.acquirer.base-url}")
                        String baseUrl) {

            return new BreakableAcquirer(builder, meters, baseUrl, java.time.Duration.ofSeconds(5));
        }
    }

    @BeforeAll
    static void startTheOtherTwoServices() {
        acquirerService = new SpringApplicationBuilder(
                        dev.kauzes.mizan.banksim.BankSimulatorApplication.class)
                .run("--spring.config.name=acquirer-test");

        ledgerService = new SpringApplicationBuilder(dev.kauzes.mizan.ledger.LedgerApplication.class)
                .run(
                        "--spring.config.name=ledger-test",
                        "--spring.datasource.url=" + MizanContainers.database("ledger"),
                        "--spring.datasource.username=" + MizanContainers.postgres().getUsername(),
                        "--spring.datasource.password=" + MizanContainers.postgres().getPassword(),
                        "--mizan.internal.service-token=" + SERVICE_TOKEN);
    }

    @AfterAll
    static void stopThem() {
        if (acquirerService != null) {
            acquirerService.close();
        }
        if (ledgerService != null) {
            ledgerService.close();
        }
    }

    @DynamicPropertySource
    static void pointAtThem(DynamicPropertyRegistry registry) {
        registry.add("mizan.acquirer.base-url", () -> urlOf(acquirerService));
        registry.add("mizan.ledger.base-url", () -> urlOf(ledgerService));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaptureResolver resolver;

    @Autowired
    private StuckPayments stuck;

    @Autowired
    private BreakableLedger ledger;

    @Autowired
    private BreakableAcquirer acquirer;

    @AfterEach
    void mendEverything() {
        ledger.broken.set(null);
        acquirer.failsBeforeSending.set(null);
        acquirer.losesTheAnswer.set(null);
    }

    @Test
    void aCaptureTheLedgerNeverRecordedIsFinishedByTheSweep() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        ledger.broken.set(new MizanException(ErrorCode.UPSTREAM_UNAVAILABLE, "The ledger could not be reached."));
        capture(merchant, payment).andExpect(status().isServiceUnavailable());

        // The state this story exists for: money taken at the acquirer, nothing in the books, and
        // until now nothing on the payment to say so.
        assertThat(statusOf(payment)).isEqualTo("AUTHORIZED");
        assertThat(entriesOf(merchant)).isZero();
        assertThat(captureStartedAt(payment))
                .as("the start of the capture was written down before the acquirer was asked")
                .isNotNull();

        ledger.broken.set(null);
        resolver.finishWhatWasInterrupted();

        assertThat(statusOf(payment)).isEqualTo("CAPTURED");
        assertThat(entriesOf(merchant)).as("one capture, one entry").isEqualTo(1);
        assertThat(captureStartedAt(payment)).as("and the mark is gone").isNull();
    }

    @Test
    void aCaptureWhoseAnswerWasLostIsFinishedToo() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        acquirer.losesTheAnswer.set(new MizanException(
                ErrorCode.UPSTREAM_TIMEOUT, "The acquirer did not answer in time."));
        capture(merchant, payment).andExpect(status().isGatewayTimeout());
        assertThat(captureStartedAt(payment)).isNotNull();

        acquirer.losesTheAnswer.set(null);
        resolver.finishWhatWasInterrupted();

        assertThat(statusOf(payment))
                .as("the acquirer says it took the money, so the books are told")
                .isEqualTo("CAPTURED");
        assertThat(entriesOf(merchant)).isEqualTo(1);
    }

    @Test
    void aCaptureThatNeverReachedTheAcquirerIsClearedAndCanBeSentAgain() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        acquirer.failsBeforeSending.set(new MizanException(
                ErrorCode.UPSTREAM_TIMEOUT, "The acquirer did not answer in time."));
        capture(merchant, payment).andExpect(status().isGatewayTimeout());
        assertThat(captureStartedAt(payment)).isNotNull();

        acquirer.failsBeforeSending.set(null);
        resolver.finishWhatWasInterrupted();

        // Not captured: the acquirer still only holds the authorization, so nothing moved and
        // recording a capture would be a lie in the books.
        assertThat(statusOf(payment)).isEqualTo("AUTHORIZED");
        assertThat(captureStartedAt(payment)).isNull();
        assertThat(entriesOf(merchant)).isZero();

        capture(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        assertThat(entriesOf(merchant)).isEqualTo(1);
    }

    @Test
    void aRefusalAtTheAcquirerLeavesNoMark() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        acquirer.failsBeforeSending.set(new MizanException(
                ErrorCode.UNPROCESSABLE, "The acquirer will not capture this authorization."));
        capture(merchant, payment).andExpect(status().isUnprocessableContent());

        assertThat(captureStartedAt(payment))
                .as("a refusal is an answer: nothing was taken, so there is nothing to finish")
                .isNull();
        assertThat(statusOf(payment)).isEqualTo("AUTHORIZED");
    }

    @Test
    void finishingTwiceAndThenCapturingAgainStillWritesOneEntry() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        ledger.broken.set(new MizanException(ErrorCode.UPSTREAM_UNAVAILABLE, "The ledger could not be reached."));
        capture(merchant, payment).andExpect(status().isServiceUnavailable());
        ledger.broken.set(null);

        // Two pods' sweeps, and the merchant trying again: all racing to finish one capture.
        resolver.finish(merchant.id, payment);
        resolver.finish(merchant.id, payment);
        capture(merchant, payment).andExpect(status().isUnprocessableContent());

        assertThat(statusOf(payment)).isEqualTo("CAPTURED");
        assertThat(entriesOf(merchant)).isEqualTo(1);
    }

    @Test
    void aLedgerThatStaysDownHandsTheCaptureToAPerson() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        ledger.broken.set(new MizanException(ErrorCode.UPSTREAM_UNAVAILABLE, "The ledger could not be reached."));
        capture(merchant, payment).andExpect(status().isServiceUnavailable());

        for (int pass = 0; pass < CaptureResolver.ATTEMPTS; pass++) {
            resolver.finishWhatWasInterrupted();
        }

        assertThat(needsAttentionSince(payment))
                .as("patient, not stuck forever: after enough passes a person is asked")
                .isNotNull();
        assertThat(stuck.everything())
                .as("and it is in the one place stuck things are looked for")
                .anySatisfy(one -> {
                    assertThat(one.paymentId()).isEqualTo(payment);
                    assertThat(one.reason()).contains("has taken the money and the ledger has not recorded it");
                });

        // The sweep leaves it alone now, even with the ledger back: a person has been asked.
        ledger.broken.set(null);
        resolver.finishWhatWasInterrupted();
        assertThat(statusOf(payment)).isEqualTo("AUTHORIZED");
    }

    // -- helpers --------------------------------------------------------------------------

    private UUID authorized(Merchant merchant) throws Exception {
        String body = bodyOf(mockMvc.perform(post(payments(merchant))
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":125000,"currency":"TRY","reference":"order-%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()));
        UUID payment = UUID.fromString(JSON.readTree(body).path("id").asString());

        mockMvc.perform(post(payments(merchant) + "/" + payment + "/authorize")
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"card\":\"" + GOOD_CARD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        return payment;
    }

    private ResultActions capture(Merchant merchant, UUID payment) throws Exception {
        return mockMvc.perform(post(payments(merchant) + "/" + payment + "/capture")
                .with(merchant.writer())
                .with(Idempotently.freshKey()));
    }

    private String statusOf(UUID payment) {
        return jdbc.queryForObject("select status from payment where id = ?", String.class, payment);
    }

    private Object captureStartedAt(UUID payment) {
        return jdbc.queryForObject(
                "select capture_started_at from payment where id = ?", Object.class, payment);
    }

    private Object needsAttentionSince(UUID payment) {
        return jdbc.queryForObject(
                "select needs_attention_since from payment where id = ?", Object.class, payment);
    }

    private long entriesOf(Merchant merchant) throws Exception {
        String entries = RestClient.builder()
                .baseUrl(urlOf(ledgerService))
                .build()
                .get()
                .uri("/api/v1/merchants/{merchantId}/entries", merchant.id)
                .header(CallerIdentity.USER_HEADER, merchant.userId.toString())
                .header(CallerIdentity.MERCHANT_HEADER, merchant.id.toString())
                .header(CallerIdentity.ROLES_HEADER, Role.ADMIN.name())
                .retrieve()
                .body(String.class);
        return JSON.readTree(entries).size();
    }

    private Merchant merchantWithASettlementAccount() {
        Merchant merchant = new Merchant(UUID.randomUUID(), UUID.randomUUID());
        RestClient.builder()
                .baseUrl(urlOf(ledgerService))
                .build()
                .post()
                .uri("/api/v1/merchants/{merchantId}/accounts", merchant.id)
                .header(CallerIdentity.USER_HEADER, merchant.userId.toString())
                .header(CallerIdentity.MERCHANT_HEADER, merchant.id.toString())
                .header(CallerIdentity.ROLES_HEADER, Role.ADMIN.name())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"code":"settlement.try","name":"Owed to the merchant, TRY",
                         "type":"LIABILITY","currency":"TRY"}
                        """)
                .retrieve()
                .toBodilessEntity();
        return merchant;
    }

    private record Merchant(UUID id, UUID userId) {

        RequestPostProcessor writer() {
            return Callers.as(userId, id, Role.ADMIN);
        }
    }

    private static String payments(Merchant merchant) {
        return "/api/v1/merchants/" + merchant.id + "/payments";
    }

    private static String urlOf(ConfigurableApplicationContext service) {
        return "http://localhost:" + service.getEnvironment().getProperty("local.server.port");
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
