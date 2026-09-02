package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.common.web.outbox.EventPublisher;
import dev.kauzes.mizan.common.web.outbox.OutboxRelay;
import dev.kauzes.mizan.common.web.outbox.PendingEvent;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
 * What the relay does with the table, and what it does when publishing fails.
 *
 * <p>The publisher here is a stand-in that can be told to refuse, which is the point: the
 * behaviour worth checking is not that a working publisher works, but what happens to the
 * events behind one that will not send. {@link OutboxKafkaTest} is where the real broker is.
 *
 * <p>The relay is driven a pass at a time rather than left to its schedule, so that what has
 * happened at each point is a fact rather than a race.
 */
@SpringBootTest(properties = {
    // The scheduled relay is switched off here. These tests drain deliberately, and a timer
    // publishing in the background would make every assertion about counts a coin toss.
    "mizan.outbox.publish-every=3650d",
    "mizan.outbox.first-retry=1s",
    "mizan.outbox.longest-retry=8s"
})
@org.springframework.context.annotation.Import(OutboxRelayTest.WithARecordingPublisher.class)
class OutboxRelayTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String GOOD_CARD = "4000000000000000";
    private static final String SERVICE_TOKEN = "a-service-token-for-tests";

    private static ConfigurableApplicationContext acquirer;
    private static ConfigurableApplicationContext ledger;

    /**
     * A publisher that records what it was given and refuses whatever it is told to refuse.
     *
     * <p>Replaces the Kafka one, so these tests need no broker and, more usefully, can produce
     * a failure on demand. A test that cannot make publishing fail cannot check any of the
     * behaviour that makes the relay worth having.
     */
    static class Recording implements EventPublisher {

        final List<PendingEvent> published = new CopyOnWriteArrayList<>();
        final Set<UUID> refuse = ConcurrentHashMap.newKeySet();

        @Override
        public void publish(PendingEvent event) {
            if (refuse.contains(event.aggregateId())) {
                throw new IllegalStateException("the broker is not having it");
            }
            published.add(event);
        }

        List<String> typesFor(UUID aggregate) {
            return published.stream()
                    .filter(event -> event.aggregateId().equals(aggregate))
                    .map(PendingEvent::type)
                    .toList();
        }
    }

    @TestConfiguration
    static class WithARecordingPublisher {

        @Bean
        @Primary
        Recording recordingPublisher() {
            return new Recording();
        }
    }

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
    private OutboxRelay relay;

    @Autowired
    private Recording publisher;

    @BeforeEach
    void clearWhateverEarlierTestsLeft() {
        // Every test in this class counts what was published, and the relay publishes
        // everything unpublished rather than only this test's rows.
        jdbc.update("update outbox_event set published_at = now() where published_at is null");
        publisher.published.clear();
        publisher.refuse.clear();
    }

    @Test
    void publishesWhatIsWaitingAndMarksIt() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);

        assertThat(unpublished()).isEqualTo(1);
        assertThat(relay.drain()).isEqualTo(1);

        assertThat(publisher.published).hasSize(1);
        PendingEvent event = publisher.published.getFirst();
        assertThat(event.type()).isEqualTo("payment.authorized");
        assertThat(event.topic())
                .as("one topic per aggregate type, decided in one place")
                .isEqualTo("mizan.payment.events");
        assertThat(event.key())
                .as("the key is the payment, which is what puts its events in one partition")
                .isEqualTo(payment.toString());
        assertThat(unpublished()).isZero();
    }

    @Test
    void drainingAgainPublishesNothing() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        authorized(merchant);

        assertThat(relay.drain()).isEqualTo(1);
        assertThat(relay.drain())
                .as("an ordinary restart re-reads the table and finds nothing to do")
                .isZero();
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    void eventsAboutOnePaymentArriveInTheOrderTheyHappened() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);
        capture(merchant, payment).andExpect(status().isOk());

        relay.drain();

        assertThat(publisher.typesFor(payment))
                .as("a consumer must not see a capture before the authorization it belongs to")
                .containsExactly("payment.authorized", "payment.captured");
    }

    @Test
    void anEventThatWillNotPublishHoldsBackItsOwnPaymentAndNothingElse() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID stuck = authorized(merchant);
        capture(merchant, stuck).andExpect(status().isOk());
        UUID fine = authorized(merchant);

        publisher.refuse.add(stuck);
        relay.drain();

        assertThat(publisher.typesFor(stuck))
                .as("its first event did not go, so its later ones must not overtake it")
                .isEmpty();
        assertThat(publisher.typesFor(fine))
                .as("and no other payment is held up by it, which is the whole point")
                .containsExactly("payment.authorized");
    }

    @Test
    void andGoesOutInOrderOnceItCanPublishAgain() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);
        capture(merchant, payment).andExpect(status().isOk());

        publisher.refuse.add(payment);
        relay.drain();
        assertThat(publisher.published).isEmpty();

        publisher.refuse.remove(payment);
        makeRetriesDue();
        relay.drain();

        assertThat(publisher.typesFor(payment))
                .containsExactly("payment.authorized", "payment.captured");
        assertThat(unpublished()).isZero();
    }

    @Test
    void aFailedEventWaitsLongerEachTime() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);
        publisher.refuse.add(payment);

        relay.drain();
        Map<String, Object> first = onlyRow();
        assertThat(first.get("attempts")).isEqualTo(1);
        assertThat(first.get("last_error"))
                .as("why it is stuck, in the row, so nobody has to find a log that has rotated")
                .asString()
                .contains("the broker is not having it");
        long firstWait = waitOf(first);

        makeRetriesDue();
        relay.drain();
        Map<String, Object> second = onlyRow();
        assertThat(second.get("attempts")).isEqualTo(2);

        assertThat(waitOf(second))
                .as("a broker that is down for an hour is retried through it, not every "
                        + "second of it")
                .isGreaterThan(firstWait);
    }

    @Test
    void anEventNotYetDueIsLeftAlone() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);
        publisher.refuse.add(payment);
        relay.drain();

        publisher.refuse.remove(payment);
        assertThat(relay.drain())
                .as("it can publish now, but its retry is not due, and hammering a broker that "
                        + "just refused is how one outage becomes two")
                .isZero();

        makeRetriesDue();
        assertThat(relay.drain()).isEqualTo(1);
    }

    @Test
    void anotherRelayHoldingAnOlderEventStopsThisOnePublishingPastIt() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();
        UUID payment = authorized(merchant);
        capture(merchant, payment).andExpect(status().isOk());

        UUID older = oldestUnpublishedFor(payment);

        // A second relay, on its own connection, claims the earlier event and holds it. This
        // is the case `for update skip locked` alone does not cover: this instance can see
        // the later event and would happily publish it first.
        try (var otherRelay = java.sql.DriverManager.getConnection(
                MizanContainers.database("payment"),
                MizanContainers.postgres().getUsername(),
                MizanContainers.postgres().getPassword())) {
            otherRelay.setAutoCommit(false);
            try (var claim = otherRelay.prepareStatement(
                    "select id from outbox_event where id = ? for update")) {
                claim.setObject(1, older);
                claim.executeQuery();
            }

            assertThat(relay.drain())
                    .as("the later event must wait rather than overtake the one being held")
                    .isZero();
            otherRelay.rollback();
        }

        // Once the other relay lets go, both go out, in order.
        relay.drain();
        assertThat(publisher.typesFor(payment))
                .containsExactly("payment.authorized", "payment.captured");
    }

    // -- reading the outbox --------------------------------------------------------------

    private long unpublished() {
        Long counted = jdbc.queryForObject(
                "select count(*) from outbox_event where published_at is null", Long.class);
        return counted == null ? 0 : counted;
    }

    private Map<String, Object> onlyRow() {
        List<Map<String, Object>> rows =
                jdbc.queryForList("select * from outbox_event where published_at is null");
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private static long waitOf(Map<String, Object> row) {
        return ((java.sql.Timestamp) row.get("next_attempt_at")).getTime();
    }

    private UUID oldestUnpublishedFor(UUID aggregate) {
        return jdbc.queryForObject(
                "select id from outbox_event where aggregate_id = ? and published_at is null "
                        + "order by sequence limit 1",
                UUID.class,
                aggregate);
    }

    /** Brings every waiting retry forward, so a test need not sleep for the backoff. */
    private void makeRetriesDue() {
        jdbc.update("update outbox_event set next_attempt_at = now() - interval '1 hour' "
                + "where published_at is null");
    }

    // -- getting a payment into the state a test needs ------------------------------------

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
}
