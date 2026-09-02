package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Giving money back.
 *
 * <p>The arithmetic is the story. A refund is the first thing here that can happen more than
 * once to one payment, and the first that gives money back, so the rule that matters is that
 * the total returned never exceeds what was taken — under concurrent requests, not only under
 * polite ones.
 *
 * <p>The acquirer and the ledger are real, as they are for capture. A refund is only correct
 * if the entry it writes is one the ledger will actually accept and the acquirer agrees about
 * how much is left.
 */
@SpringBootTest
class RefundTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String GOOD_CARD = "4000000000000000";
    private static final String SERVICE_TOKEN = "a-service-token-for-tests";
    private static final long CAPTURED = 125000;

    private static ConfigurableApplicationContext acquirer;
    private static ConfigurableApplicationContext ledger;

    @BeforeAll
    static void startTheOtherTwoServices() {
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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void refundsInPartAndThenTheRest() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);

        refund(merchant, payment, 25000, "return-one-item")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(25000))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.acquirerReference").isNotEmpty())
                .andExpect(jsonPath("$.ledgerEntryId").isNotEmpty());

        mockMvc.perform(get(payments(merchant) + "/" + payment).with(merchant.writer()))
                .andExpect(jsonPath("$.refundedAmount").value(25000))
                .andExpect(jsonPath("$.refundableAmount").value(100000))
                .andExpect(jsonPath("$.status")
                        .value("CAPTURED"));

        refund(merchant, payment, 100000, "return-the-rest").andExpect(status().isCreated());

        mockMvc.perform(get(payments(merchant) + "/" + payment).with(merchant.writer()))
                .andExpect(jsonPath("$.refundedAmount").value(CAPTURED))
                .andExpect(jsonPath("$.refundableAmount").value(0))
                .andExpect(jsonPath("$.status")
                        .value("CAPTURED"));
    }

    @Test
    void theEntryReversesTheCaptureAndNamesIt() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);
        String captureEntry = fieldOf(merchant, payment, "ledgerEntryId");

        String body = bodyOf(refund(merchant, payment, 25000, "partial"));
        String refundEntry = JSON.readTree(body).path("ledgerEntryId").asString();

        String entry = entryIn(merchant, refundEntry);
        assertThat(entry)
                .as("exactly the capture, the other way up")
                .contains("\"accountCode\":\"platform.clearing.try\"")
                .contains("-25000")
                .contains("25000");
        assertThat(JSON.readTree(entry).path("corrects").asString())
                .as("and it names the capture it gives back, so both are readable together")
                .isEqualTo(captureEntry);

        // Nothing was deleted or edited. The capture is still there, in full.
        assertThat(entryIn(merchant, captureEntry)).contains("125000");
        assertThat(entriesOf(merchant)).isEqualTo(2);
    }

    @Test
    void willNotGiveBackMoreThanWasTaken() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);
        refund(merchant, payment, 100000, "most-of-it").andExpect(status().isCreated());

        refund(merchant, payment, 25001, "too-much")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("Only 25000 of this payment is left to refund, and 25001 was "
                                + "asked for."));

        // Nothing happened: not at the acquirer, not in the books, not on the payment.
        assertThat(entriesOf(merchant)).isEqualTo(2);
        assertThat(refundedAmountOf(payment)).isEqualTo(100000);
    }

    @Test
    void willNotGiveBackMoreThanWasTakenWhenAskedAllAtOnce() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);

        // Ten callers each asking for a fifth of the payment. Four can be paid and six cannot,
        // and which four is nobody's business — what matters is that exactly 125000 goes back.
        // Without a lock each would read the same remaining amount and all ten would proceed.
        int callers = 10;
        long each = CAPTURED / 5;
        List<Callable<Integer>> attempts = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            String reference = "concurrent-" + i;
            attempts.add(() -> mockMvc.perform(refunding(merchant, payment, each, reference))
                    .andReturn()
                    .getResponse()
                    .getStatus());
        }

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Integer>> results = pool.invokeAll(attempts);
            long succeeded = 0;
            for (Future<Integer> result : results) {
                if (result.get() == 201) {
                    succeeded++;
                }
            }
            assertThat(succeeded)
                    .as("five fifths fit and no more, whatever order they arrived in")
                    .isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }

        assertThat(refundedAmountOf(payment)).isEqualTo(CAPTURED);
        assertThat(entriesOf(merchant))
                .as("one entry for the capture and one for each refund that was made")
                .isEqualTo(6);
    }

    @Test
    void aRefundSentTwiceGivesBackOnce() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);

        String first = bodyOf(refund(merchant, payment, 25000, "same-reference"));
        String second = bodyOf(refund(merchant, payment, 25000, "same-reference"));

        assertThat(JSON.readTree(second).path("id").asString())
                .as("the caller is asking the same question again, not a new one")
                .isEqualTo(JSON.readTree(first).path("id").asString());
        assertThat(refundedAmountOf(payment)).isEqualTo(25000);
        assertThat(entriesOf(merchant)).isEqualTo(2);
    }

    @Test
    void aPaymentThatWasNeverCapturedCannotBeRefunded() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        refund(merchant, payment, 1000, "nope")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("A payment that is AUTHORIZED cannot be refunded. Only money "
                                + "that was captured can be given back; releasing a "
                                + "reservation is a void."));
        assertThat(entriesOf(merchant)).isZero();
    }

    @Test
    void aRefundOfNothingOrOfLessThanNothingIsRefused() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);

        mockMvc.perform(refunding(merchant, payment, 0, "zero"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("a refund has to be for more than nothing"));
        mockMvc.perform(refunding(merchant, payment, -5000, "negative"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aRefundInAnotherCurrencyIsAMistakeRatherThanAConversion() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);

        mockMvc.perform(post(payments(merchant) + "/" + payment + "/refunds")
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":25000,"currency":"USD","reference":"wrong-currency"}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("This payment was taken in TRY and can only be refunded in TRY."));
    }

    @Test
    void refundsAreListedAgainstThePayment() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);
        refund(merchant, payment, 25000, "one").andExpect(status().isCreated());
        refund(merchant, payment, 30000, "two").andExpect(status().isCreated());

        mockMvc.perform(get(payments(merchant) + "/" + payment + "/refunds")
                        .with(merchant.writer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void cannotRefundAnotherMerchantsPayment() throws Exception {
        Merchant mine = merchantWithASettlementAccount();
        Merchant theirs = merchantWithASettlementAccount();
        UUID theirPayment = captured(theirs);

        mockMvc.perform(refunding(mine, theirPayment, 1000, "not-mine"))
                .andExpect(status().isNotFound());
        assertThat(refundedAmountOf(theirPayment)).isZero();
    }

    @Test
    void readingIsNotRefunding() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);

        mockMvc.perform(post(payments(merchant) + "/" + payment + "/refunds")
                        .with(Callers.as(merchant.userId, merchant.id, Role.VIEWER))
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000,\"reference\":\"viewer\"}"))
                .andExpect(status().isForbidden());
        assertThat(refundedAmountOf(payment)).isZero();
    }

    @Test
    void theBooksStillBalanceAfterwards() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = captured(merchant);
        refund(merchant, payment, 25000, "one").andExpect(status().isCreated());
        refund(merchant, payment, 100000, "the-rest").andExpect(status().isCreated());

        String report = RestClient.builder()
                .baseUrl(urlOf(ledger))
                .build()
                .get()
                .uri("/actuator/ledgerintegrity")
                .retrieve()
                .body(String.class);

        assertThat(JSON.readTree(report).path("sound").asBoolean())
                .as("every currency still sums to zero: %s", report)
                .isTrue();
    }

    // -- getting a payment into the state a test needs -------------------------------------

    private UUID captured(Merchant merchant) throws Exception {
        UUID payment = authorized(merchant);
        mockMvc.perform(post(payments(merchant) + "/" + payment + "/capture")
                        .with(merchant.writer())
                        .with(Idempotently.freshKey()))
                .andExpect(status().isOk());
        return payment;
    }

    private UUID authorized(Merchant merchant) throws Exception {
        UUID payment = created(merchant);
        mockMvc.perform(post(payments(merchant) + "/" + payment + "/authorize")
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"card\":\"" + GOOD_CARD + "\"}"))
                .andExpect(status().isOk());
        return payment;
    }

    private UUID created(Merchant merchant) throws Exception {
        String body = bodyOf(mockMvc.perform(post(payments(merchant))
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":%d,"currency":"TRY","reference":"order-%s"}
                                """.formatted(CAPTURED, UUID.randomUUID())))
                .andExpect(status().isCreated()));

        return UUID.fromString(JSON.readTree(body).path("id").asString());
    }

    private ResultActions refund(Merchant merchant, UUID payment, long amount, String reference)
            throws Exception {

        return mockMvc.perform(refunding(merchant, payment, amount, reference));
    }

    private MockHttpServletRequestBuilder refunding(
            Merchant merchant, UUID payment, long amount, String reference) {

        return post(payments(merchant) + "/" + payment + "/refunds")
                .with(merchant.writer())
                .with(Idempotently.freshKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":%d,"reference":"%s","reason":"the customer sent it back"}
                        """.formatted(amount, reference));
    }

    // -- reading ---------------------------------------------------------------------------

    private long refundedAmountOf(UUID payment) {
        Long amount = jdbc.queryForObject(
                "select refunded_amount from payment where id = ?", Long.class, payment);
        return amount == null ? 0 : amount;
    }

    private String fieldOf(Merchant merchant, UUID payment, String field) throws Exception {
        String body = bodyOf(
                mockMvc.perform(get(payments(merchant) + "/" + payment).with(merchant.writer())));
        return JSON.readTree(body).path(field).asString();
    }

    private String ledgerAs(Merchant merchant, String path) {
        return RestClient.builder()
                .baseUrl(urlOf(ledger))
                .build()
                .get()
                .uri("/api/v1/merchants/{merchantId}/entries" + path, merchant.id)
                .header(CallerIdentity.USER_HEADER, merchant.userId.toString())
                .header(CallerIdentity.MERCHANT_HEADER, merchant.id.toString())
                .header(CallerIdentity.ROLES_HEADER, Role.ADMIN.name())
                .retrieve()
                .body(String.class);
    }

    private long entriesOf(Merchant merchant) {
        return JSON.readTree(ledgerAs(merchant, "")).size();
    }

    private String entryIn(Merchant merchant, String entryId) {
        return ledgerAs(merchant, "/" + entryId);
    }

    // -- merchants ---------------------------------------------------------------------------

    private Merchant merchantWithASettlementAccount() {
        Merchant merchant = new Merchant(UUID.randomUUID(), UUID.randomUUID());
        RestClient.builder()
                .baseUrl(urlOf(ledger))
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
