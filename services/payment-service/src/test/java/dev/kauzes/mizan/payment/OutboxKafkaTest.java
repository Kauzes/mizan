package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.common.web.outbox.OutboxRelay;
import dev.kauzes.mizan.common.web.outbox.Topics;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The events actually arriving, on a real broker, read by a real consumer.
 *
 * <p>{@link OutboxRelayTest} checks what the relay does, against a publisher it can make fail.
 * This checks the one thing that cannot be faked: that what this service puts on a topic is
 * something somebody else can read, keyed the way it says, in the order it promises. That is
 * a question about a wire, and only a wire answers it.
 */
@SpringBootTest(properties = {
    // Driven a pass at a time here too, so that "the consumer saw two messages" is a fact
    // about what was published rather than about when a timer happened to fire.
    "mizan.outbox.publish-every=3650d"
})
class OutboxKafkaTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GOOD_CARD = "4000000000000000";
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
    private OutboxRelay relay;

    @Test
    @Timeout(120)
    void aPaymentsEventsReachTheTopicInOrderAndUnderItsOwnKey() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();

        // Subscribed before anything is published, so the test reads what this run produced
        // rather than whatever an earlier one left on the topic.
        try (KafkaConsumer<String, String> consumer = subscribed()) {

            UUID payment = authorized(merchant);
            capture(merchant, payment).andExpect(status().isOk());
            drainEverything();

            List<ConsumerRecord<String, String>> received = consume(consumer, 2);

            assertThat(received).hasSize(2);
            assertThat(received.stream().map(ConsumerRecord::key).distinct())
                    .as("both keyed by the payment, which is what keeps them in one partition")
                    .containsExactly(payment.toString());
            assertThat(received.stream().map(record -> typeOf(record)).toList())
                    .as("and in the order they happened")
                    .containsExactly("payment.authorized", "payment.captured");
            assertThat(received.stream().map(ConsumerRecord::partition).distinct())
                    .as("one key, one partition; ordering means nothing across partitions")
                    .hasSize(1);
        }
    }

    @Test
    @Timeout(120)
    void theMessageCarriesTheEnvelopeThePayloadAndHeadersToRouteOn() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();

        try (KafkaConsumer<String, String> consumer = subscribed()) {
            UUID payment = authorized(merchant);
            drainEverything();

            ConsumerRecord<String, String> record = consume(consumer, 1).getFirst();
            JsonNode message = JSON.readTree(record.value());

            assertThat(message.path("type").asString()).isEqualTo("payment.authorized");
            assertThat(message.path("version").asInt()).isEqualTo(1);
            assertThat(message.path("aggregateId").asString()).isEqualTo(payment.toString());
            assertThat(message.path("merchantId").asString()).isEqualTo(merchant.id.toString());
            assertThat(message.path("eventId").asString()).isNotBlank();
            assertThat(message.path("payload").path("acquirerReference").asString()).isNotBlank();
            assertThat(record.value())
                    .as("the card is not on the wire, and is not in this service to put there")
                    .doesNotContain(GOOD_CARD);

            // Repeated as headers so a consumer deciding whether it cares, or an operator
            // looking at what is stuck, does not have to parse a body to find out what it is.
            assertThat(headerOf(record, "event-type")).isEqualTo("payment.authorized");
            assertThat(headerOf(record, "event-id"))
                    .isEqualTo(message.path("eventId").asString());
            assertThat(headerOf(record, "correlation-id")).isNotBlank();
        }
    }

    @Test
    @Timeout(120)
    void drainingTwicePutsOneMessageOnTheTopic() throws Exception {
        Merchant merchant = merchantWithASettlementAccount();

        try (KafkaConsumer<String, String> consumer = subscribed()) {
            UUID payment = authorized(merchant);

            drainEverything();
            drainEverything();

            // At least once is the promise, and the ordinary case has to be exactly once or
            // the promise is worthless. What is not claimed is that a crash between publishing
            // and marking cannot repeat one; that window is real, and MIZ-49 is the answer.
            assertThat(consume(consumer, 1)).hasSize(1);
            assertThat(consume(consumer, 1))
                    .as("and there is not a second copy waiting behind it")
                    .isEmpty();
            assertThat(payment).isNotNull();
        }
    }

    // -- the consumer ---------------------------------------------------------------------

    private KafkaConsumer<String, String> subscribed() {
        Properties settings = new Properties();
        settings.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                MizanContainers.kafka().getBootstrapServers());
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        settings.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        settings.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(settings);
        consumer.subscribe(List.of(Topics.of("payment")));
        // Force the assignment now, so that "latest" means from here rather than from
        // wherever the topic happens to be when the first poll gets round to it.
        consumer.poll(Duration.ofSeconds(10));
        return consumer;
    }

    /** Reads until it has what it was asked for, or until waiting stops being reasonable. */
    private static List<ConsumerRecord<String, String>> consume(
            KafkaConsumer<String, String> consumer, int wanted) {

        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(20).toNanos();

        while (received.size() < wanted && System.nanoTime() < giveUpAt) {
            consumer.poll(Duration.ofMillis(500)).forEach(received::add);
        }
        return received;
    }

    private static String typeOf(ConsumerRecord<String, String> record) {
        return JSON.readTree(record.value()).path("type").asString();
    }

    private static String headerOf(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Passes until there is nothing left, because one pass takes one batch. */
    private void drainEverything() {
        while (relay.drain() > 0) {
            // keep going
        }
    }

    // -- getting a payment into the state a test needs ------------------------------------

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
