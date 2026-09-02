package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.common.web.outbox.DomainEvent;
import dev.kauzes.mizan.common.web.outbox.Outbox;
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
 * Whether an event can ever disagree with the state that caused it.
 *
 * <p>It can, in the obvious design. Change the payment then publish, and a failed publish
 * leaves money moved that nobody was told about; publish then change, and a failed change
 * leaves the platform announcing something that did not happen. Both are silent. The answer
 * is that the event is a row in the same database, written in the same transaction, so there
 * is no ordering of failures that separates them.
 *
 * <p>The test that matters here is the rolled back one. Everything else is bookkeeping.
 */
@SpringBootTest
class OutboxTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String GOOD_CARD = "4000000000000000";
    private static final String NO_FUNDS = "4000000000000002";
    private static final String SERVICE_TOKEN = "a-service-token-for-tests";

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

    @Autowired
    private Outbox outbox;

    @Test
    void aCaptureAndTheAnnouncementOfItAreWrittenTogether() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        capture(merchant, payment).andExpect(status().isOk());

        Map<String, Object> event = onlyEventOf(payment, "payment.captured");
        assertThat(event.get("aggregate_type")).isEqualTo("payment");
        assertThat(event.get("merchant_id")).hasToString(merchant.id.toString());
        assertThat(event.get("version")).isEqualTo(1);
        assertThat(event.get("published_at"))
                .as("nothing publishes yet, and a row that claimed otherwise would be a lie")
                .isNull();

        Map<String, Object> payload = payloadOf(event);
        assertThat(payload)
                .containsEntry("paymentId", payment.toString())
                .containsEntry("amount", 125000)
                .containsEntry("currency", "TRY");
        assertThat(payload.get("ledgerEntryId"))
                .as("where in the books it landed, so a consumer need not ask this service")
                .isNotNull();
    }

    @Test
    void aCaptureThatFailsAnnouncesNothing() throws Exception {
        // This merchant never opened a settlement account, so the ledger refuses the entry
        // and the whole capture transaction rolls back. The event has to go with it: an
        // announcement of a capture that did not happen is worse than no announcement.
        Merchant merchant = merchant();
        UUID payment = authorized(merchant);

        capture(merchant, payment).andExpect(status().isUnprocessableContent());

        assertThat(statusOf(payment)).isEqualTo("AUTHORIZED");
        assertThat(eventsOf(payment, "payment.captured"))
                .as("the state rolled back, so the event that described it rolled back too")
                .isEmpty();
    }

    @Test
    void andThenAnnouncesItOnceWhenItSucceeds() throws Exception {
        Merchant merchant = merchant();
        UUID payment = authorized(merchant);
        capture(merchant, payment).andExpect(status().isUnprocessableContent());

        openSettlementAccount(merchant);
        capture(merchant, payment).andExpect(status().isOk());

        assertThat(eventsOf(payment, "payment.captured"))
                .as("one capture, one event, whatever happened on the way")
                .hasSize(1);
    }

    @Test
    void anAuthorizationAndADeclineBothAnnounceThemselves() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();

        UUID approved = created(merchant);
        mockMvc.perform(authorizing(merchant, approved, GOOD_CARD).with(Idempotently.freshKey()))
                .andExpect(status().isOk());
        assertThat(payloadOf(onlyEventOf(approved, "payment.authorized")))
                .containsEntry("cardLastFour", "0000")
                .containsKey("acquirerReference");

        UUID refused = created(merchant);
        mockMvc.perform(authorizing(merchant, refused, NO_FUNDS).with(Idempotently.freshKey()))
                .andExpect(status().isOk());
        assertThat(payloadOf(onlyEventOf(refused, "payment.declined")))
                .as("the acquirer's own word for it, because a person will be asked why")
                .containsEntry("reason", "insufficient_funds");
    }

    @Test
    void aVoidAnnouncesItselfAndKeepsTheReason() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        mockMvc.perform(post(payments(merchant) + "/" + payment + "/void")
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"the customer cancelled the order\"}"))
                .andExpect(status().isOk());

        assertThat(payloadOf(onlyEventOf(payment, "payment.voided")))
                .containsEntry("reason", "the customer cancelled the order");
    }

    @Test
    void anIntentAnnouncesNothing() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = created(merchant);

        // Nobody was contacted and no money moved. An event here would be this service
        // narrating its own database to anyone who would listen.
        assertThat(allEventsOf(payment)).isEmpty();
    }

    @Test
    void aPayloadIsWrittenForConsumersAndNotScrapedOffTheEntity() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        Map<String, Object> payload = payloadOf(onlyEventOf(payment, "payment.authorized"));

        // The card number is nowhere in this service, which is what makes it easy to keep
        // out of a topic that several services and a broker's disk will hold.
        assertThat(payload.toString()).doesNotContain(GOOD_CARD);
        // Nor does the payload carry the payment's whole history, its version column, or
        // anything else that is this service's business rather than a consumer's.
        assertThat(payload.keySet())
                .containsExactlyInAnyOrder(
                        "paymentId", "merchantId", "amount", "currency", "reference",
                        "acquirerReference", "cardLastFour", "at");
    }

    @Test
    void everyEventCarriesTheRequestThatCausedIt() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = created(merchant);

        mockMvc.perform(authorizing(merchant, payment, GOOD_CARD)
                        .with(Idempotently.freshKey())
                        .header("X-Correlation-Id", "the-request-that-did-it"))
                .andExpect(status().isOk());

        assertThat(onlyEventOf(payment, "payment.authorized").get("correlation_id"))
                .as("so a trace crosses the gap between a call and what happened because of it")
                .isEqualTo("the-request-that-did-it");
    }

    @Test
    void anEventCannotBeRecordedOutsideATransaction() {
        // The one mistake this arrangement exists to prevent. A row written on its own commits
        // on its own, and then it is no longer tied to anything.
        assertThatThrownBy(() -> outbox.record(DomainEvent.of(
                        PaymentEvents.Type.CAPTURED,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Map.of("paymentId", UUID.randomUUID().toString()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no transaction here");
    }

    @Test
    void anEventIsARecordOfWhatHappenedAndCannotBeRewritten() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);
        capture(merchant, payment).andExpect(status().isOk());

        UUID eventId = UUID.fromString(onlyEventOf(payment, "payment.captured").get("id").toString());

        // Going around the application entirely, because a rule that only holds for well
        // behaved application code is a comment rather than a rule.
        assertThatThrownBy(() -> jdbc.update(
                        "update outbox_event set payload = '{\"amount\": 1}'::jsonb where id = ?",
                        eventId))
                .hasStackTraceContaining("cannot be changed");

        jdbc.update("update outbox_event set published_at = now() where id = ?", eventId);
        assertThatThrownBy(() -> jdbc.update(
                        "update outbox_event set published_at = null where id = ?", eventId))
                .as("and a published event cannot be quietly unpublished to be sent again")
                .hasStackTraceContaining("cannot be unpublished");
    }

    // -- reading the outbox -------------------------------------------------------------

    private List<Map<String, Object>> eventsOf(UUID payment, String type) {
        return jdbc.queryForList(
                "select * from outbox_event where aggregate_id = ? and type = ? "
                        + "order by occurred_at",
                payment,
                type);
    }

    private List<Map<String, Object>> allEventsOf(UUID payment) {
        return jdbc.queryForList("select * from outbox_event where aggregate_id = ?", payment);
    }

    private Map<String, Object> onlyEventOf(UUID payment, String type) {
        List<Map<String, Object>> found = eventsOf(payment, type);
        assertThat(found).as("exactly one %s for %s", type, payment).hasSize(1);
        return found.getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(Map<String, Object> event) {
        return JSON.readValue(event.get("payload").toString(), Map.class);
    }

    private String statusOf(UUID payment) {
        return jdbc.queryForObject("select status from payment where id = ?", String.class, payment);
    }

    // -- getting a payment into the state a test needs -----------------------------------

    private UUID authorized(Merchant merchant) throws Exception {
        UUID payment = created(merchant);
        mockMvc.perform(authorizing(merchant, payment, GOOD_CARD).with(Idempotently.freshKey()))
                .andExpect(status().isOk());
        return payment;
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

    private ResultActions capture(Merchant merchant, UUID payment) throws Exception {
        return mockMvc.perform(post(payments(merchant) + "/" + payment + "/capture")
                .with(merchant.writer())
                .with(Idempotently.freshKey()));
    }

    private MockHttpServletRequestBuilder authorizing(
            Merchant merchant, UUID payment, String card) {

        return post(payments(merchant) + "/" + payment + "/authorize")
                .with(merchant.writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"card\":\"" + card + "\"}");
    }

    private Merchant merchantWithASettlementAccount() {
        Merchant merchant = merchant();
        openSettlementAccount(merchant);
        return merchant;
    }

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
}
