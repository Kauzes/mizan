package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.common.identity.ServiceCredential;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
 * Where a payment stops being a promise and becomes a movement.
 *
 * <p>Both other systems are real here. The acquirer is the simulator and the ledger is the
 * ledger, each started in this JVM with its own database and its own configuration file. The
 * question this story has to answer is whether two services with two databases end up
 * agreeing, and a stub on either side would answer a different question.
 *
 * <p>What is checked, mostly, is what each failure leaves behind. Every step is repeatable,
 * so the interesting cases are the ones where a step succeeds and the next does not.
 */
@SpringBootTest
class CaptureTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String GOOD_CARD = "4000000000000000";
    private static final String NO_FUNDS = "4000000000000002";

    /** The same one the test configuration hands both services. */
    private static final String SERVICE_TOKEN = "a-service-token-for-tests";

    private static ConfigurableApplicationContext acquirer;
    private static ConfigurableApplicationContext ledger;

    @BeforeAll
    static void startTheOtherTwoServices() {
        acquirer = new SpringApplicationBuilder(
                        dev.kauzes.mizan.banksim.BankSimulatorApplication.class)
                .run("--spring.config.name=acquirer-test");

        ledger = new SpringApplicationBuilder(
                        dev.kauzes.mizan.ledger.LedgerApplication.class)
                .run(
                        "--spring.config.name=ledger-test",
                        "--spring.datasource.url=" + MizanContainers.database("ledger"),
                        "--spring.datasource.username="
                                + MizanContainers.postgres().getUsername(),
                        "--spring.datasource.password="
                                + MizanContainers.postgres().getPassword(),
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
    void aCaptureTakesTheMoneyAndPutsItInTheBooks() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        String body = bodyOf(capture(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.ledgerEntryId").isNotEmpty())
                .andExpect(jsonPath("$.allowedNext.length()").value(0)));

        String entryId = JSON.readTree(body).path("ledgerEntryId").asString();

        // And the entry is really there, in the other service's database, saying what a
        // capture means: the platform holds more at the acquirer, the merchant is owed more.
        String entry = entryIn(merchant, entryId);
        assertThat(entry)
                .contains("platform.clearing.try")
                .contains("settlement.try")
                .contains("125000")
                .contains("-125000");
    }

    @Test
    void aVoidReleasesTheMoneyAndPostsNothing() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        mockMvc.perform(voiding(merchant, payment)
                        .with(Idempotently.freshKey())
                        .content("{\"reason\":\"the customer cancelled the order\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.ledgerEntryId").doesNotExist());

        // No money moved, so the books have nothing to say. An entry recording a movement
        // that did not happen would be worse than no entry at all.
        assertThat(entriesOf(merchant)).isZero();
        assertThat(historyOf(payment))
                .as("and the reason is kept on the step, so somebody reading it later sees why")
                .contains("the customer cancelled the order");
    }

    @Test
    void aVoidNeedsNoReason() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        mockMvc.perform(voiding(merchant, payment).with(Idempotently.freshKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));
    }

    @Test
    void aCaptureWhoseAnswerWasLostIsFinishedByRepeatingIt() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        String first = JSON.readTree(bodyOf(capture(merchant, payment)))
                .path("ledgerEntryId")
                .asString();

        // A caller that never heard the answer sends it again. The acquirer says the money is
        // already taken, the ledger answers with the entry it already wrote, and the payment
        // ends where it was. Nothing is taken twice and nothing is recorded twice.
        mockMvc.perform(capturing(merchant, payment).with(Idempotently.freshKey()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("A payment that is CAPTURED cannot be captured. That is where "
                                + "this payment ends."));

        assertThat(entriesOf(merchant))
                .as("one capture, one entry, however many times it was asked for")
                .isEqualTo(1);
        assertThat(first).isNotBlank();
    }

    @Test
    void aRetryWithTheSameKeyIsAnsweredWithTheFirstResult() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);
        String key = UUID.randomUUID().toString();

        String first = bodyOf(mockMvc.perform(
                capturing(merchant, payment).with(Idempotently.key(key))));
        String second = bodyOf(mockMvc.perform(
                capturing(merchant, payment).with(Idempotently.key(key))));

        assertThat(JSON.readTree(second))
                .as("a retry of a capture is the same capture")
                .isEqualTo(JSON.readTree(first));
        assertThat(entriesOf(merchant)).isEqualTo(1);
    }

    @Test
    void aCaptureTheBooksWillNotTakeLeavesThePaymentAuthorized() throws Exception {
        // This merchant never opened a settlement account, so the ledger has nowhere to put
        // the money. The refusal happens after the acquirer has taken it, which is the honest
        // consequence of taking the money before recording it.
        Merchant merchant = merchant();
        UUID payment = authorized(merchant);

        capture(merchant, payment)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("The books would not accept this movement: No account "
                                + "settlement.try in this merchant's books. It has to be "
                                + "opened before money can be recorded as arriving in it."));

        assertThat(statusOf(payment))
                .as("not captured, because nothing recorded it; the state never runs ahead "
                        + "of the books")
                .isEqualTo("AUTHORIZED");
        assertThat(ledgerEntryOf(payment)).isNull();
    }

    @Test
    void andIsFinishedOnceTheAccountExists() throws Exception {
        Merchant merchant = merchant();
        UUID payment = authorized(merchant);
        capture(merchant, payment).andExpect(status().isUnprocessableContent());

        openSettlementAccount(merchant);

        // The acquirer already took the money on the first attempt and says so rather than
        // taking it again; the ledger writes the entry; the payment finishes. This is why
        // every step of a capture has to be repeatable.
        capture(merchant, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.ledgerEntryId").isNotEmpty());
        assertThat(entriesOf(merchant)).isEqualTo(1);
    }

    @Test
    void aPaymentThatWasNeverAuthorizedCannotBeCaptured() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = created(merchant);

        capture(merchant, payment)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("A payment that is CREATED cannot be captured. It can only "
                                + "become [AUTHORIZATION_UNKNOWN, AUTHORIZED, DECLINED]."));

        // Refused before the acquirer is troubled, so nothing outside the platform did work
        // on our behalf for a request that was never going to be allowed.
        assertThat(entriesOf(merchant)).isZero();
    }

    @Test
    void aDeclinedPaymentCannotBeCapturedOrVoided() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = created(merchant);
        mockMvc.perform(authorizing(merchant, payment, NO_FUNDS).with(Idempotently.freshKey()))
                .andExpect(status().isOk());

        capture(merchant, payment).andExpect(status().isUnprocessableContent());
        mockMvc.perform(voiding(merchant, payment).with(Idempotently.freshKey()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("A payment that is DECLINED cannot be voided. That is where "
                                + "this payment ends."));
    }

    @Test
    void aCapturedPaymentCannotThenBeVoided() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);
        capture(merchant, payment).andExpect(status().isOk());

        // Releasing money that has already been taken is not a void, it is a refund, and a
        // refund is an entry rather than the absence of one. Epic 7.
        mockMvc.perform(voiding(merchant, payment).with(Idempotently.freshKey()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("A payment that is CAPTURED cannot be voided. That is where "
                                + "this payment ends."));
        assertThat(entriesOf(merchant)).isEqualTo(1);
    }

    @Test
    void cannotCaptureAnotherMerchantsPayment() throws Exception {
        Merchant mine = merchantWithASettlementAccount();
        Merchant theirs = merchantWithASettlementAccount();
        UUID theirPayment = authorized(theirs);

        mockMvc.perform(capturing(mine, theirPayment).with(Idempotently.freshKey()))
                .andExpect(status().isNotFound());
        assertThat(entriesOf(theirs)).isZero();
    }

    @Test
    void readingIsNotCapturing() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        mockMvc.perform(post(payments(merchant) + "/" + payment + "/capture")
                        .with(Callers.as(merchant.userId, merchant.id, Role.VIEWER))
                        .with(Idempotently.freshKey()))
                .andExpect(status().isForbidden());
        assertThat(entriesOf(merchant)).isZero();
    }

    @Test
    void theLedgerRefusesThisServiceWithoutItsCredential() {
        // The credential is what separates a service call from a merchant's. Worth a test of
        // its own, because everything above would keep passing if the ledger stopped checking.
        assertThat(postingWith(null)).as("with nothing at all").isEqualTo(401);
        assertThat(postingWith("not-the-token")).as("with a guess").isEqualTo(401);
    }

    /** Posts an empty entry with whatever credential, and reports only the status. */
    private int postingWith(String credential) {
        RestClient.RequestBodySpec request = RestClient.builder()
                .baseUrl(urlOf(ledger))
                .build()
                .post()
                .uri("/internal/entries")
                .contentType(MediaType.APPLICATION_JSON);
        if (credential != null) {
            request = request.header(ServiceCredential.HEADER, credential);
        }
        return request.body("{}").exchange((sent, response) -> response.getStatusCode().value());
    }

    // -- getting a payment into the state a test needs ------------------------------------

    private UUID authorized(Merchant merchant) throws Exception {
        UUID payment = created(merchant);
        mockMvc.perform(authorizing(merchant, payment, GOOD_CARD).with(Idempotently.freshKey()))
                .andExpect(status().isOk());
        return payment;
    }

    private UUID created(Merchant merchant) throws Exception {
        String body = bodyOf(mockMvc.perform(post(payments(merchant))
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":125000,"currency":"TRY","reference":"order-%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()));

        return UUID.fromString(JSON.readTree(body).path("id").asString());
    }

    private ResultActions capture(Merchant merchant, UUID payment) throws Exception {
        return mockMvc.perform(capturing(merchant, payment).with(Idempotently.freshKey()));
    }

    private MockHttpServletRequestBuilder capturing(Merchant merchant, UUID payment) {
        return post(payments(merchant) + "/" + payment + "/capture").with(merchant.writer());
    }

    private MockHttpServletRequestBuilder voiding(Merchant merchant, UUID payment) {
        return post(payments(merchant) + "/" + payment + "/void")
                .with(merchant.writer())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder authorizing(
            Merchant merchant, UUID payment, String card) {

        return post(payments(merchant) + "/" + payment + "/authorize")
                .with(merchant.writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"card\":\"" + card + "\"}");
    }

    // -- reading the other service's books, over its own API ------------------------------

    /** Whatever the ledger says this merchant's entries are, read the way anybody would. */
    private String entriesOf(Merchant merchant, String path) {
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

    private long entriesOf(Merchant merchant) throws Exception {
        return JSON.readTree(entriesOf(merchant, "")).size();
    }

    private String entryIn(Merchant merchant, String entryId) {
        return entriesOf(merchant, "/" + entryId);
    }

    // -- and this service's own row, for the things an API would not show ------------------

    private String statusOf(UUID payment) {
        return jdbc.queryForObject("select status from payment where id = ?", String.class, payment);
    }

    private Object ledgerEntryOf(UUID payment) {
        return jdbc.queryForObject(
                "select ledger_entry_id from payment where id = ?", Object.class, payment);
    }

    private String historyOf(UUID payment) {
        return jdbc.queryForList(
                        "select reason from payment_transition where payment_id = ?",
                        String.class,
                        payment)
                .toString();
    }

    // -- merchants ------------------------------------------------------------------------

    private Merchant merchantWithASettlementAccount() throws Exception {
        Merchant merchant = merchant();
        openSettlementAccount(merchant);
        return merchant;
    }

    /**
     * Opened through the ledger's own API, because that is how a merchant gets one. The
     * ledger does not create accounts because a capture arrived, and this test does not
     * pretend it does.
     */
    private void openSettlementAccount(Merchant merchant) {
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

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
