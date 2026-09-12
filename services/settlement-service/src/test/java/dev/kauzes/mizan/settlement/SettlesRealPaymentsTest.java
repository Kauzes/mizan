package dev.kauzes.mizan.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * That this service settles the payments the payment service really captures.
 *
 * <p>{@link SettlementTest} writes captures into this service's own table, which is right for
 * testing the batch and says nothing about whether a real event can be read. The fields this
 * service reaches for — the amount, the currency, when the money moved, the acquirer's
 * reference — are a contract between two services, and a fixture stays the right shape only
 * for as long as somebody keeps it so. Risk found exactly that: a field it needed was not on
 * the event at all, and every test passed.
 *
 * <p>So this starts the real payment service, with the real acquirer and ledger behind it,
 * takes payments through it, and settles whatever arrives. Nothing here builds an event.
 */
@SpringBootTest(properties = "mizan.settlement.close-every=3650d")
class SettlesRealPaymentsTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GOOD_CARD = "4000000000000000";
    private static final String SERVICE_TOKEN = "a-service-token-for-tests";

    private static ConfigurableApplicationContext acquirer;
    private static ConfigurableApplicationContext ledger;
    private static ConfigurableApplicationContext payments;

    @BeforeAll
    static void startTheRestOfThePlatform() {
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

        payments = new SpringApplicationBuilder(
                        dev.kauzes.mizan.payment.PaymentApplication.class)
                .run(
                        "--spring.config.name=payment-test",
                        "--spring.datasource.url=" + MizanContainers.database("payment"),
                        "--spring.datasource.username=" + MizanContainers.postgres().getUsername(),
                        "--spring.datasource.password=" + MizanContainers.postgres().getPassword(),
                        "--spring.kafka.bootstrap-servers="
                                + MizanContainers.kafka().getBootstrapServers(),
                        "--mizan.internal.service-token=" + SERVICE_TOKEN,
                        "--mizan.acquirer.base-url=" + urlOf(acquirer),
                        "--mizan.ledger.base-url=" + urlOf(ledger));
    }

    @AfterAll
    static void stopThem() {
        for (ConfigurableApplicationContext service : List.of(payments, ledger, acquirer)) {
            if (service != null) {
                service.close();
            }
        }
    }

    @DynamicPropertySource
    static void useAGroupOfOurOwn(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.kafka.consumer.group-id",
                () -> "settlement-contract-" + UUID.randomUUID());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Settlement settlement;

    @Test
    @Timeout(240)
    void settlesWhatWasReallyCaptured() {
        UUID merchant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        openSettlementAccount(merchant, user);

        captureAPaymentOf(merchant, user, 100_00);
        captureAPaymentOf(merchant, user, 200_00);

        eventuallyWaiting(merchant, 2);

        // Nothing here built an event. The payment service published them, this service read
        // them off the topic, and this batch is what it made of them.
        Settlement.Closed closed = settlement.close(merchant, LocalDate.now().plusDays(1), "TRY");

        assertThat(closed.captured().amount()).isEqualTo(300_00L);
        assertThat(closed.captured().currency().getCurrencyCode()).isEqualTo("TRY");
        assertThat(closed.payments()).isEqualTo(2);
        assertThat(closed.net().amount()).isLessThan(closed.captured().amount());
    }

    @Test
    @Timeout(240)
    void keepsTheAcquirersReferenceBecauseReconciliationWillNeedIt() {
        UUID merchant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        openSettlementAccount(merchant, user);

        captureAPaymentOf(merchant, user, 50_00);
        eventuallyWaiting(merchant, 1);

        // The reference a bank statement will name this payment by. It is on the event now,
        // and it cannot be asked for later: reconciliation happens days after the capture,
        // and reaching into the payment database for it is the boundary this service keeps.
        List<String> references = jdbc.queryForList(
                "select acquirer_reference from settleable where merchant_id = ?",
                String.class,
                merchant);

        assertThat(references).hasSize(1);
        assertThat(references.getFirst())
                .as("a capture says which acquirer reference it was, or nothing can reconcile it")
                .isNotBlank();
    }

    @Test
    @Timeout(240)
    void settlesACapturedPaymentOnceHoweverOftenItIsDelivered() {
        UUID merchant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        openSettlementAccount(merchant, user);

        captureAPaymentOf(merchant, user, 75_00);
        eventuallyWaiting(merchant, 1);

        // The relay marks an event published after sending it, so anything that dies in
        // between sends again. A capture counted twice is a merchant paid twice, and the
        // second payment is somebody else's money.
        assertThat(waiting(merchant)).isEqualTo(1);
    }

    @Test
    @Timeout(240)
    void settlesNothingFromAPaymentThatWasOnlyAuthorized() {
        UUID merchant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        openSettlementAccount(merchant, user);

        authorizeAPaymentOf(merchant, user, 400_00, GOOD_CARD);
        captureAPaymentOf(merchant, user, 10_00);

        // The capture arriving is the signal that the authorization's event has had its
        // chance: both were published in order, on the same topic, for the same merchant.
        eventuallyWaiting(merchant, 1);

        // An authorization is a promise that the money is there. Settling one would be paying
        // a merchant for a payment that might still be voided.
        assertThat(waiting(merchant)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select sum(amount) from settleable where merchant_id = ?",
                        Long.class,
                        merchant))
                .isEqualTo(10_00L);
    }

    @Test
    @Timeout(240)
    void putsACaptureInTheDayTheMoneyMovedRatherThanTheDayItWasHeardAbout() {
        UUID merchant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        openSettlementAccount(merchant, user);

        captureAPaymentOf(merchant, user, 20_00);
        eventuallyWaiting(merchant, 1);

        Map<String, Object> row = jdbc.queryForMap(
                "select captured_at, settled_for from settleable where merchant_id = ?", merchant);

        // The day comes from the event's own moment. A consumer that was down for an hour
        // must not shift payments into a day they did not happen in.
        assertThat(row.get("settled_for")).isNotNull();
        assertThat(row.get("captured_at")).isNotNull();
    }

    // -- driving the real payment service ---------------------------------------------------

    private void captureAPaymentOf(UUID merchant, UUID user, long amount) {
        UUID payment = authorizeAPaymentOf(merchant, user, amount, GOOD_CARD);
        asMerchant(payments, merchant, user)
                .post()
                .uri(
                        "/api/v1/merchants/{merchantId}/payments/{paymentId}/capture",
                        merchant,
                        payment)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .retrieve()
                .toBodilessEntity();
    }

    private UUID authorizeAPaymentOf(UUID merchant, UUID user, long amount, String card) {
        String created = asMerchant(payments, merchant, user)
                .post()
                .uri("/api/v1/merchants/{merchantId}/payments", merchant)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"amount":%d,"currency":"TRY","reference":"order-%s"}
                        """.formatted(amount, UUID.randomUUID()))
                .retrieve()
                .body(String.class);

        UUID payment = UUID.fromString(JSON.readTree(created).path("id").asString());

        asMerchant(payments, merchant, user)
                .post()
                .uri(
                        "/api/v1/merchants/{merchantId}/payments/{paymentId}/authorize",
                        merchant,
                        payment)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"card\":\"" + card + "\"}")
                .retrieve()
                .toBodilessEntity();

        return payment;
    }

    private void openSettlementAccount(UUID merchant, UUID user) {
        asMerchant(ledger, merchant, user)
                .post()
                .uri("/api/v1/merchants/{merchantId}/accounts", merchant)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"code":"settlement.try","name":"Owed to the merchant, TRY",
                         "type":"LIABILITY","currency":"TRY"}
                        """)
                .retrieve()
                .toBodilessEntity();
    }

    private static RestClient asMerchant(
            ConfigurableApplicationContext service, UUID merchant, UUID user) {

        return RestClient.builder()
                .baseUrl(urlOf(service))
                .defaultHeader(CallerIdentity.USER_HEADER, user.toString())
                .defaultHeader(CallerIdentity.MERCHANT_HEADER, merchant.toString())
                .defaultHeader(CallerIdentity.ROLES_HEADER, Role.ADMIN.name())
                .build();
    }

    // -- waiting for what arrived -----------------------------------------------------------

    private void eventuallyWaiting(UUID merchant, int howMany) {
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < giveUpAt) {
            if (waiting(merchant) >= howMany) {
                return;
            }
            sleep(250);
        }
        throw new AssertionError(
                "settlement never heard about " + howMany + " capture(s) for " + merchant
                        + "; saw " + waiting(merchant));
    }

    private long waiting(UUID merchant) {
        Long counted = jdbc.queryForObject(
                "select count(*) from settleable where merchant_id = ?", Long.class, merchant);
        return counted == null ? 0 : counted;
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
