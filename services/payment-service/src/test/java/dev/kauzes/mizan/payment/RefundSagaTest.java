package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.payment.PaymentRequests.RefundRequest;
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
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a refund leaves behind when it is interrupted, and how it is finished afterwards.
 *
 * <p>The three failures that matter are all silent. The acquirer gave the money back and the
 * ledger never recorded it, so the books say the platform holds money it does not. The ledger
 * recorded it and the refund was never marked, so a retry is safe but nothing retries. The
 * acquirer was asked and did not answer, so nobody knows whether the money moved.
 *
 * <p>They are injected rather than argued about: the ledger and the acquirer are wrapped in
 * things this test can switch off at a chosen moment. That is the only way to check the state
 * a crash leaves, which is the whole subject of this story.
 *
 * <p>The sweep is off here, so each test drives the resumption itself and what has happened at
 * each point is a fact rather than a race.
 */
@SpringBootTest(properties = "mizan.refunds.resolve-every=3650d")
@Import({RefundSagaTest.WithInterruptibleDependencies.class})
class RefundSagaTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GOOD_CARD = "4000000000000000";
    private static final String SERVICE_TOKEN = "a-service-token-for-tests";
    private static final long CAPTURED = 125000;

    private static ConfigurableApplicationContext acquirerService;
    private static ConfigurableApplicationContext ledgerService;

    /** A ledger that can be told to stop working, standing in for one that has fallen over. */
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
        public UUID recordRefund(
                UUID merchantId, Payment payment, long amount, String reference) {

            RuntimeException failure = broken.get();
            if (failure != null) {
                throw failure;
            }
            return super.recordRefund(merchantId, payment, amount, reference);
        }
    }

    /** An acquirer that can be told to stop answering, or to refuse. */
    static class BreakableAcquirer extends AcquirerClient {

        final AtomicReference<RuntimeException> broken = new AtomicReference<>();

        BreakableAcquirer(
                RestClient.Builder builder, String baseUrl, java.time.Duration timeout) {
            super(builder, baseUrl, timeout);
        }

        @Override
        public AcquirerRefund refund(String acquirerReference, String reference, long amount) {
            RuntimeException failure = broken.get();
            if (failure != null) {
                throw failure;
            }
            return super.refund(acquirerReference, reference, amount);
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

            return new BreakableLedger(
                    builder, baseUrl, java.time.Duration.ofSeconds(5), serviceToken);
        }

        @Bean
        @Primary
        BreakableAcquirer breakableAcquirer(
                RestClient.Builder builder,
                @org.springframework.beans.factory.annotation.Value("${mizan.acquirer.base-url}")
                        String baseUrl) {

            return new BreakableAcquirer(builder, baseUrl, java.time.Duration.ofSeconds(5));
        }
    }

    @BeforeAll
    static void startTheOtherTwoServices() {
        acquirerService = new SpringApplicationBuilder(
                        dev.kauzes.mizan.banksim.BankSimulatorApplication.class)
                .run("--spring.config.name=acquirer-test");

        ledgerService = new SpringApplicationBuilder(
                        dev.kauzes.mizan.ledger.LedgerApplication.class)
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
    private RefundService refundService;

    @Autowired
    private RefundResolver resolver;

    @Autowired
    private BreakableLedger ledger;

    @Autowired
    private BreakableAcquirer acquirer;

    @AfterEach
    void mendEverything() {
        ledger.broken.set(null);
        acquirer.broken.set(null);
    }

    @Test
    void theLedgerFallingOverAfterTheMoneyWentBackLeavesARecordOfIt() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);

        ledger.broken.set(new MizanException(
                ErrorCode.UPSTREAM_UNAVAILABLE, "The ledger could not be reached."));

        assertThatThrownBy(() -> refundService.refund(merchant.id, payment, asked(25000, "r1")))
                .isInstanceOf(MizanException.class);

        // MIZ-51 would have left nothing here at all: the row was written only once
        // everything worked, so a crash during the call lost the fact that money went back.
        assertThat(statusOfRefund(payment, "r1"))
                .as("the money is gone and the books do not say so, and the platform knows it")
                .isEqualTo("RETURNED");
        assertThat(refundedAmountOf(payment))
                .as("and the reservation is held, so it cannot be refunded again meanwhile")
                .isEqualTo(25000);
        assertThat(entriesOf(merchant))
                .as("nothing in the books yet, which is exactly the state being recovered from")
                .isEqualTo(1);
    }

    @Test
    void andTheSweepFinishesItFromThereWithoutAskingTheAcquirerAgain() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);
        ledger.broken.set(new MizanException(ErrorCode.UPSTREAM_UNAVAILABLE, "no ledger"));
        assertThatThrownBy(() -> refundService.refund(merchant.id, payment, asked(25000, "r1")))
                .isInstanceOf(MizanException.class);

        // The ledger comes back, and the acquirer is switched off. If resuming restarted the
        // refund rather than continuing it, this would fail — which is the point.
        ledger.broken.set(null);
        acquirer.broken.set(new IllegalStateException("the acquirer must not be asked again"));

        makeDue();
        resolver.finishWhatWasInterrupted();

        assertThat(statusOfRefund(payment, "r1")).isEqualTo("SUCCEEDED");
        assertThat(entriesOf(merchant)).isEqualTo(2);
        assertThat(booksBalance()).isTrue();
    }

    @Test
    void anAcquirerThatSaysNothingLeavesTheRefundWaitingRatherThanFailed() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);

        acquirer.broken.set(new MizanException(
                ErrorCode.UPSTREAM_TIMEOUT,
                "The acquirer did not answer in time. Whether the money has gone back is not "
                        + "yet known."));

        assertThatThrownBy(() -> refundService.refund(merchant.id, payment, asked(25000, "r1")))
                .isInstanceOf(MizanException.class);

        // Nobody knows whether the money moved, so nothing claims it did or did not.
        assertThat(statusOfRefund(payment, "r1")).isEqualTo("REQUESTED");
        assertThat(refundedAmountOf(payment))
                .as("the reservation is held, because deciding it failed would let the "
                        + "merchant send the same money back twice")
                .isEqualTo(25000);
        assertThat(entriesOf(merchant)).isEqualTo(1);
    }

    @Test
    void andIsFinishedByAskingTheAcquirerAgainOnceItAnswers() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);
        acquirer.broken.set(new MizanException(ErrorCode.UPSTREAM_TIMEOUT, "no answer"));
        assertThatThrownBy(() -> refundService.refund(merchant.id, payment, asked(25000, "r1")))
                .isInstanceOf(MizanException.class);

        acquirer.broken.set(null);
        makeDue();
        resolver.finishWhatWasInterrupted();

        assertThat(statusOfRefund(payment, "r1")).isEqualTo("SUCCEEDED");
        assertThat(refundedAmountOf(payment)).isEqualTo(25000);
        assertThat(entriesOf(merchant)).isEqualTo(2);
        assertThat(booksBalance()).isTrue();
    }

    @Test
    void anAcquirerThatRefusesOutrightGivesTheReservationBack() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);

        acquirer.broken.set(new MizanException(
                ErrorCode.UNPROCESSABLE, "The acquirer will not refund this payment."));

        assertThatThrownBy(() -> refundService.refund(merchant.id, payment, asked(25000, "r1")))
                .isInstanceOf(MizanException.class);

        // "It said no" and "it said nothing" are different facts. This one is a refusal, so
        // nothing moved and the merchant gets their headroom back.
        assertThat(statusOfRefund(payment, "r1")).isEqualTo("FAILED");
        assertThat(refundedAmountOf(payment)).isZero();
        assertThat(entriesOf(merchant)).isEqualTo(1);
    }

    @Test
    void resumingTwiceFinishesOnce() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);
        ledger.broken.set(new MizanException(ErrorCode.UPSTREAM_UNAVAILABLE, "no ledger"));
        assertThatThrownBy(() -> refundService.refund(merchant.id, payment, asked(25000, "r1")))
                .isInstanceOf(MizanException.class);
        ledger.broken.set(null);

        makeDue();
        resolver.finishWhatWasInterrupted();
        makeDue();
        resolver.finishWhatWasInterrupted();
        resolver.finishWhatWasInterrupted();

        assertThat(entriesOf(merchant))
                .as("a resumption that raced another must not reverse the money twice")
                .isEqualTo(2);
        assertThat(refundedAmountOf(payment)).isEqualTo(25000);
        assertThat(booksBalance()).isTrue();
    }

    @Test
    void oneNobodyCanFinishStopsBeingRetriedAndBecomesSomebodysProblem() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);
        ledger.broken.set(new MizanException(ErrorCode.UPSTREAM_UNAVAILABLE, "no ledger, ever"));
        assertThatThrownBy(() -> refundService.refund(merchant.id, payment, asked(25000, "r1")))
                .isInstanceOf(MizanException.class);

        for (int attempt = 0; attempt < RefundService.ATTEMPTS + 1; attempt++) {
            makeDue();
            resolver.finishWhatWasInterrupted();
        }

        assertThat(statusOfRefund(payment, "r1"))
                .as("retried a bounded number of times and then left for a person")
                .isEqualTo("ABANDONED");
        assertThat(lastErrorOfRefund(payment, "r1")).contains("no ledger, ever");
        assertThat(refundedAmountOf(payment))
                .as("and it keeps its reservation, because the money may well have gone back")
                .isEqualTo(25000);

        // And it is no longer swept, so one broken refund does not become a service doing
        // nothing else.
        makeDue();
        resolver.finishWhatWasInterrupted();
        assertThat(statusOfRefund(payment, "r1")).isEqualTo("ABANDONED");
    }

    @Test
    void theBooksBalanceAfterEveryOneOfThoseRecoveries() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);

        ledger.broken.set(new MizanException(ErrorCode.UPSTREAM_UNAVAILABLE, "no ledger"));
        assertThatThrownBy(() -> refundService.refund(merchant.id, payment, asked(25000, "r1")))
                .isInstanceOf(MizanException.class);
        assertThat(booksBalance()).as("balanced while the refund is half done").isTrue();

        ledger.broken.set(null);
        makeDue();
        resolver.finishWhatWasInterrupted();
        assertThat(booksBalance()).as("and balanced once it is finished").isTrue();

        acquirer.broken.set(new MizanException(ErrorCode.UPSTREAM_TIMEOUT, "no answer"));
        assertThatThrownBy(() -> refundService.refund(merchant.id, payment, asked(100000, "r2")))
                .isInstanceOf(MizanException.class);
        assertThat(booksBalance()).as("and while one is unknown").isTrue();

        acquirer.broken.set(null);
        makeDue();
        resolver.finishWhatWasInterrupted();
        assertThat(booksBalance()).as("and after that one recovers too").isTrue();
        assertThat(refundedAmountOf(payment)).isEqualTo(CAPTURED);
    }

    // -- helpers ---------------------------------------------------------------------------

    private static RefundRequest asked(long amount, String reference) {
        return new RefundRequest(amount, null, reference, "the customer sent it back");
    }

    /** Brings every waiting attempt forward, so a test need not sleep through the backoff. */
    private void makeDue() {
        jdbc.update("update refund set next_attempt_at = now() - interval '1 hour', "
                + "created_at = created_at - interval '1 hour' "
                + "where status in ('REQUESTED', 'RETURNED')");
    }

    private String statusOfRefund(UUID payment, String reference) {
        return jdbc.queryForObject(
                "select status from refund where payment_id = ? and reference = ?",
                String.class,
                payment,
                reference);
    }

    private String lastErrorOfRefund(UUID payment, String reference) {
        return jdbc.queryForObject(
                "select last_error from refund where payment_id = ? and reference = ?",
                String.class,
                payment,
                reference);
    }

    private long refundedAmountOf(UUID payment) {
        Long amount = jdbc.queryForObject(
                "select refunded_amount from payment where id = ?", Long.class, payment);
        return amount == null ? 0 : amount;
    }

    private boolean booksBalance() {
        String report = RestClient.builder()
                .baseUrl(urlOf(ledgerService))
                .build()
                .get()
                .uri("/actuator/ledgerintegrity")
                .retrieve()
                .body(String.class);
        return JSON.readTree(report).path("sound").asBoolean();
    }

    private long entriesOf(Merchant merchant) {
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

    // -- getting a payment into the state a test needs -------------------------------------

    private UUID captured(Merchant merchant) throws Exception {
        UUID payment = created(merchant);
        mockMvc.perform(post(payments(merchant) + "/" + payment + "/authorize")
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"card\":\"" + GOOD_CARD + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(payments(merchant) + "/" + payment + "/capture")
                        .with(merchant.writer())
                        .with(Idempotently.freshKey()))
                .andExpect(status().isOk());
        return payment;
    }

    private UUID created(Merchant merchant) throws Exception {
        ResultActions created = mockMvc.perform(post(payments(merchant))
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":%d,"currency":"TRY","reference":"order-%s"}
                                """.formatted(CAPTURED, UUID.randomUUID())))
                .andExpect(status().isCreated());

        String body = created.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(JSON.readTree(body).path("id").asString());
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

        org.springframework.test.web.servlet.request.RequestPostProcessor writer() {
            return Callers.as(userId, id, Role.ADMIN);
        }
    }

    private static String payments(Merchant merchant) {
        return "/api/v1/merchants/" + merchant.id + "/payments";
    }

    private static String urlOf(ConfigurableApplicationContext service) {
        return "http://localhost:" + service.getEnvironment().getProperty("local.server.port");
    }
}
