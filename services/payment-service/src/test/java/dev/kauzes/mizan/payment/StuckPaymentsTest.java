package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * What happens to a payment nobody can finish.
 *
 * <p>By this point there are two ways to end up somewhere no amount of retrying will move you:
 * an authorization the acquirer has no record of, and a refund whose saga gave up. Each was
 * handled correctly by the story that created it, and each was invisible unless you knew which
 * different place to look in. This is the one place that answers "what is stuck, and why".
 */
@SpringBootTest(properties = {
    // The sweeps are driven by hand here, so what has happened at each point is a fact.
    "mizan.acquirer.resolve-every=3650d",
    "mizan.acquirer.resolve-after=0s",
    "mizan.refunds.resolve-every=3650d"
})
class StuckPaymentsTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SERVICE_TOKEN = "a-service-token-for-tests";

    private static ConfigurableApplicationContext acquirerService;
    private static ConfigurableApplicationContext ledgerService;

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
        registry.add("mizan.acquirer.timeout", () -> "1s");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthorizationResolver resolver;

    @Autowired
    private UnknownOutcomes unknownOutcomes;

    @Autowired
    private StuckEndpoint endpoint;

    @Autowired
    private StuckPayments stuck;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearWhatEarlierTestsLeft() {
        // Decisions are deliberately not cleared: the table refuses to be deleted from, which
        // is the point of it and is asserted below. Every test asks about its own payment.
        jdbc.update("update payment set needs_attention_since = null, attention_reason = null, "
                + "attention_handled_at = null, resolve_attempts = 0 "
                + "where needs_attention_since is not null");
        jdbc.update("update refund set attention_handled_at = now() "
                + "where status = 'ABANDONED' and attention_handled_at is null");
    }

    @Test
    void aPaymentTheAcquirerHasNoRecordOfStopsBeingSweptForever() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        unknownOutcomes.record(merchant.id, payment, "the acquirer never answered");

        // The acquirer has no record of it, so asking is not going to work. MIZ-44 asked
        // anyway, on every pass, forever.
        for (int attempt = 0; attempt < AuthorizationResolver.ATTEMPTS; attempt++) {
            resolver.resolveWhatIsUnknown();
        }

        assertThat(statusOf(payment))
                .as("still exactly as unknown as it was: giving up is not an answer")
                .isEqualTo("AUTHORIZATION_UNKNOWN");
        assertThat(needsAPerson(payment)).isTrue();
        assertThat(attentionReasonOf(payment))
                .contains("The acquirer has no record of this payment after");

        // And it is out of the sweep, so the platform stops spending its time on it.
        long before = resolveAttemptsOf(payment);
        resolver.resolveWhatIsUnknown();
        assertThat(resolveAttemptsOf(payment))
                .as("no longer swept, because asking has been established not to work")
                .isEqualTo(before);
    }

    @Test
    void andAppearsInTheOnePlaceThatSaysWhatIsStuck() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        unknownOutcomes.record(merchant.id, payment, "the acquirer never answered");
        giveUpOn(payment);

        Map<String, Object> report = endpoint.whatIsStuck();
        assertThat(report.get("total")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.INTEGER)
                .isGreaterThanOrEqualTo(1);

        // Found by id rather than by position. This database is shared with every other test
        // in this JVM, and a test that assumes it is the only thing that has ever gone wrong
        // is a test that fails for reasons that have nothing to do with it.
        StuckPayments.Stuck one = stuckPayment(payment);

        assertThat(one.kind()).isEqualTo("PAYMENT");
        assertThat(one.paymentId()).isEqualTo(payment);
        assertThat(one.whatWeBelieve()).isEqualTo("AUTHORIZATION_UNKNOWN");
        assertThat(one.whatTheAcquirerBelieves())
                .as("what we think and what they think, side by side, because that comparison "
                        + "is the first thing anybody does")
                .isEqualTo("no record of this payment");
        assertThat(one.reason()).isNotBlank();
        assertThat(one.since()).isNotNull();
    }

    @Test
    void anAbandonedRefundIsShownBesideWhatTheAcquirerSaysAboutItsPayment() throws Exception {
        Merchant merchant = merchant();
        UUID payment = capturedThroughTheAcquirer(merchant);
        UUID refund = abandonedRefundOn(payment, merchant);

        StuckPayments.Stuck one = stuck.everything().stream()
                .filter(s -> s.id().equals(refund))
                .findFirst()
                .orElseThrow();

        assertThat(one.kind()).isEqualTo("REFUND");
        assertThat(one.paymentId()).isEqualTo(payment);
        assertThat(one.whatWeBelieve()).isEqualTo("ABANDONED");
        assertThat(one.reason()).contains("the ledger would not take it");

        // Here the comparison earns its place: the platform could not finish the refund, and
        // the acquirer knows perfectly well what it did with the payment. A person looking at
        // this does not have to go and ask separately.
        assertThat(one.whatTheAcquirerBelieves().toString())
                .as("what we think and what they think, side by side")
                .contains("APPROVED");
    }

    @Test
    void anAbandonedRefundCanBeRetriedOrClosedByAPersonToo() throws Exception {
        Merchant merchant = merchant();
        UUID payment = capturedThroughTheAcquirer(merchant);
        UUID refund = abandonedRefundOn(payment, merchant);

        endpoint.decide("refund", refund.toString(), "CLOSED", "ada", "settled by hand");

        assertThat(stuck.everything()).noneMatch(s -> s.id().equals(refund));
        assertThat(statusOfRefund(refund))
                .as("abandoned stays true: nobody could finish it, and that is a fact about "
                        + "the refund rather than about the person who looked at it")
                .isEqualTo("ABANDONED");
        assertThat(refundedAmountOf(payment))
                .as("and its reservation is untouched, because the money may well have gone")
                .isEqualTo(25000);
    }

    @Test
    void anOperatorCanRetryOneAndItGoesBackToBeingThePlatformsProblem() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        unknownOutcomes.record(merchant.id, payment, "the acquirer never answered");
        giveUpOn(payment);

        endpoint.decide("payment", payment.toString(), "RETRY", "ada", "the acquirer is back");

        assertThat(needsAPerson(payment)).isFalse();
        assertThat(resolveAttemptsOf(payment))
                .as("from attempt zero, because the old count describes a world that has gone")
                .isZero();

        // And it is being swept again, which is what "back to being the platform's problem"
        // actually means.
        resolver.resolveWhatIsUnknown();
        assertThat(resolveAttemptsOf(payment)).isEqualTo(1);
    }

    @Test
    void anOperatorCanCloseOneAndItStopsAppearingWithoutAnythingMoving() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        unknownOutcomes.record(merchant.id, payment, "the acquirer never answered");
        giveUpOn(payment);

        endpoint.decide(
                "payment",
                payment.toString(),
                "CLOSED",
                "grace",
                "the acquirer confirmed by telephone that nothing was ever reserved");

        assertThat(isStuck(payment)).isFalse();
        assertThat(statusOf(payment))
                .as("nothing about the money changed: a person looked, which is a different "
                        + "and more honest thing than the platform pretending it worked it out")
                .isEqualTo("AUTHORIZATION_UNKNOWN");

        // And closing means stop. It did not, at first: closing reset the attempt count, so
        // the sweep picked the payment up, asked five more times and stranded it again. The
        // smoke check found that by refusing to pass on a stack where somebody had closed one.
        for (int attempt = 0; attempt < AuthorizationResolver.ATTEMPTS + 2; attempt++) {
            resolver.resolveWhatIsUnknown();
        }
        assertThat(isStuck(payment))
                .as("closed is closed, and does not quietly become stuck again")
                .isFalse();
    }

    @Test
    void everyDecisionSaysWhoWhenWhyAndWhatItChanged() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        unknownOutcomes.record(merchant.id, payment, "the acquirer never answered");
        giveUpOn(payment);

        endpoint.decide("payment", payment.toString(), "CLOSED", "grace", "confirmed by telephone");

        List<Map<String, Object>> history = stuck.historyOf("PAYMENT", payment);
        assertThat(history).hasSize(1);
        Map<String, Object> decision = history.getFirst();
        assertThat(decision.get("decision")).isEqualTo("CLOSED");
        assertThat(decision.get("decided_by")).isEqualTo("grace");
        assertThat(decision.get("why")).isEqualTo("confirmed by telephone");
        assertThat(decision.get("changed"))
                .as("what it changed rather than what was intended, so the record is of an "
                        + "effect and is checkable afterwards")
                .asString()
                .contains("no longer needs attention");
        assertThat(decision.get("at")).isNotNull();
    }

    @Test
    void aDecisionCannotBeRewrittenOrRemoved() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        unknownOutcomes.record(merchant.id, payment, "the acquirer never answered");
        giveUpOn(payment);
        endpoint.decide("payment", payment.toString(), "CLOSED", "grace", "confirmed");

        // Around the application entirely, because a rule that only holds for well behaved
        // application code is a comment rather than a rule.
        assertThatThrownBy(() -> jdbc.update(
                        "update operator_decision set decided_by = 'somebody else'"))
                .hasStackTraceContaining("cannot be");
        assertThatThrownBy(() -> jdbc.update("delete from operator_decision"))
                .hasStackTraceContaining("cannot be");
    }

    @Test
    void aDecisionNobodyOwnsOrExplainedIsRefused() throws Exception {
        Merchant merchant = merchant();
        UUID payment = created(merchant);
        unknownOutcomes.record(merchant.id, payment, "the acquirer never answered");
        giveUpOn(payment);

        assertThat(endpoint.decide("payment", payment.toString(), "CLOSED", "", "why"))
                .containsKey("error");
        assertThat(endpoint.decide("payment", payment.toString(), "CLOSED", "ada", " "))
                .containsKey("error");
        assertThat(endpoint.decide("payment", payment.toString(), "MAKE_IT_WORK", "ada", "please"))
                .containsEntry("error", "decision must be RETRY or CLOSED");

        assertThat(isStuck(payment))
                .as("and none of those did anything")
                .isTrue();
    }

    // -- helpers ---------------------------------------------------------------------------

    /**
     * A refund nobody could finish.
     *
     * <p>Built through the domain model rather than by writing SQL, so it is a refund the rest
     * of the service would recognise. How a refund actually comes to be abandoned is
     * RefundSagaTest's subject; this one is about what an operator sees afterwards.
     */
    private UUID abandonedRefundOn(UUID payment, Merchant merchant) {
        return new org.springframework.transaction.support.TransactionTemplate(
                        transactionManager)
                .execute(status -> {
            Payment captured = paymentRepository.findById(payment).orElseThrow();
            captured.refunded(25000);

            Refund refund = new Refund(captured, 25000, "gave-up", "the customer sent it back");
            refund.abandoned("the ledger would not take it, five times");
            refundRepository.saveAndFlush(refund);
            return refund.id();
        });
    }

    private UUID capturedThroughTheAcquirer(Merchant merchant) throws Exception {
        openSettlementAccount(merchant);
        UUID payment = created(merchant);
        mockMvc.perform(post(payments(merchant) + "/" + payment + "/authorize")
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"card\":\"4000000000000000\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(payments(merchant) + "/" + payment + "/capture")
                        .with(merchant.writer())
                        .with(Idempotently.freshKey()))
                .andExpect(status().isOk());
        return payment;
    }

    /** Opened through the ledger's own API, because that is how a merchant gets one. */
    private void openSettlementAccount(Merchant merchant) {
        org.springframework.web.client.RestClient.builder()
                .baseUrl(urlOf(ledgerService))
                .build()
                .post()
                .uri("/api/v1/merchants/{merchantId}/accounts", merchant.id)
                .header(dev.kauzes.mizan.common.identity.CallerIdentity.USER_HEADER,
                        merchant.userId.toString())
                .header(dev.kauzes.mizan.common.identity.CallerIdentity.MERCHANT_HEADER,
                        merchant.id.toString())
                .header(dev.kauzes.mizan.common.identity.CallerIdentity.ROLES_HEADER,
                        Role.ADMIN.name())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"code":"settlement.try","name":"Owed to the merchant, TRY",
                         "type":"LIABILITY","currency":"TRY"}
                        """)
                .retrieve()
                .toBodilessEntity();
    }

    private String statusOfRefund(UUID refund) {
        return jdbc.queryForObject("select status from refund where id = ?", String.class, refund);
    }

    private long refundedAmountOf(UUID payment) {
        Long amount = jdbc.queryForObject(
                "select refunded_amount from payment where id = ?", Long.class, payment);
        return amount == null ? 0 : amount;
    }

    /** This payment's entry in the one place that says what is stuck. */
    private StuckPayments.Stuck stuckPayment(UUID payment) {
        return stuck.everything().stream()
                .filter(one -> one.paymentId().equals(payment) && "PAYMENT".equals(one.kind()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(payment + " is not listed as stuck"));
    }

    private boolean isStuck(UUID payment) {
        return stuck.everything().stream()
                .anyMatch(one -> one.paymentId().equals(payment) && "PAYMENT".equals(one.kind()));
    }

    /** Drives the resolver until it has given up, whatever the limit happens to be. */
    private void giveUpOn(UUID payment) {
        for (int attempt = 0; attempt < AuthorizationResolver.ATTEMPTS + 1; attempt++) {
            resolver.resolveWhatIsUnknown();
        }
        if (!needsAPerson(payment)) {
            throw new AssertionError("the resolver never gave up on " + payment);
        }
    }

    private String statusOf(UUID payment) {
        return jdbc.queryForObject("select status from payment where id = ?", String.class, payment);
    }

    private boolean needsAPerson(UUID payment) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select needs_attention_since is not null from payment where id = ?",
                Boolean.class,
                payment));
    }

    private String attentionReasonOf(UUID payment) {
        return jdbc.queryForObject(
                "select attention_reason from payment where id = ?", String.class, payment);
    }

    private long resolveAttemptsOf(UUID payment) {
        Long attempts = jdbc.queryForObject(
                "select resolve_attempts from payment where id = ?", Long.class, payment);
        return attempts == null ? 0 : attempts;
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

    private record Merchant(UUID id, UUID userId) {

        org.springframework.test.web.servlet.request.RequestPostProcessor writer() {
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
}
