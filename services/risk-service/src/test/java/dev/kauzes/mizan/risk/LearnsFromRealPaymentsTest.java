package dev.kauzes.mizan.risk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.time.Duration;
import java.util.List;
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
 * That this service learns from the events the payment service really publishes.
 *
 * <p>{@link ObservedBehaviourTest} records observations directly, which is right for testing
 * what a baseline does with them and says nothing about whether a real event can be read. The
 * fields this service reaches for — the amount, the currency, the last four digits — are a
 * contract between two services, and a fixture is only the right shape for as long as somebody
 * keeps it so.
 *
 * <p>So this starts the real payment service, with the real acquirer and ledger behind it,
 * takes payments through it, and asserts the baseline moved. Nothing here builds an event.
 */
@SpringBootTest
class LearnsFromRealPaymentsTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GOOD_CARD = "4000000000000000";
    private static final String NO_FUNDS = "4000000000000002";
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
        registry.add("spring.kafka.consumer.group-id", () -> "risk-contract-" + UUID.randomUUID());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Timeout(240)
    void learnsWhatAMerchantNormallyTakesFromTheirRealPayments() {
        UUID merchant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        openSettlementAccount(merchant, user);

        // Enough for a baseline to mean something, at an amount this test chose and nothing in
        // this service knows in advance.
        for (int i = 0; i < 12; i++) {
            captureAPaymentOf(merchant, user, 7_50 + i);
        }

        // Nothing here built an event. The payment service published them, this service read
        // them off the topic, and the baseline is what it made of them.
        long typical = eventuallyABaselineFor(merchant);
        assertThat(typical)
                .as("about what this merchant actually takes, learned rather than configured")
                .isBetween(7_50L, 7_62L);
    }

    @Test
    @Timeout(240)
    void recognisesACardThatHasPaidThisMerchantBefore() {
        UUID merchant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        openSettlementAccount(merchant, user);

        for (int i = 0; i < 12; i++) {
            captureAPaymentOf(merchant, user, 7_50);
        }
        eventuallyABaselineFor(merchant);

        // The captured event did not carry the card at all until a live check noticed that
        // risk could only ever see one on a *declined* payment — so the rule that recognises a
        // returning customer could never fire, and the scorer could only ever grow more
        // suspicious of somebody the longer they stayed.
        assertThat(cardsSeenFor(merchant))
                .as("a captured payment says which card paid, or nothing can learn a card is good")
                .isNotEmpty();
    }

    @Test
    @Timeout(240)
    void aDeclinedPaymentIsRememberedWithoutMovingTheBaseline() {
        UUID merchant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        openSettlementAccount(merchant, user);

        for (int i = 0; i < 12; i++) {
            captureAPaymentOf(merchant, user, 5_00);
        }
        eventuallyABaselineFor(merchant);

        // A refused payment for a great deal more. It must be remembered as a decline and must
        // not become part of what this merchant normally takes.
        authorizeAPaymentOf(merchant, user, 900_000_00, NO_FUNDS);

        long giveUpAt = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < giveUpAt) {
            if (declinesSeenFor(merchant) > 0) {
                break;
            }
            sleep(250);
        }

        assertThat(declinesSeenFor(merchant))
                .as("the decline was read off the topic and recorded")
                .isEqualTo(1);
        assertThat(typicalAmountOf(merchant))
                .as("and did not move what this merchant is thought to normally take")
                .isEqualTo(5_00);
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

    // -- waiting for what was learned -------------------------------------------------------

    private long eventuallyABaselineFor(UUID merchant) {
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < giveUpAt) {
            if (paymentsSeenBy(merchant) >= 12) {
                return typicalAmountOf(merchant);
            }
            sleep(250);
        }
        throw new AssertionError(
                "risk never learned anything about " + merchant + "; saw "
                        + paymentsSeenBy(merchant) + " payment(s)");
    }

    private long typicalAmountOf(UUID merchant) {
        Long amount = jdbc.queryForObject(
                "select typical_amount from merchant_baseline where merchant_id = ?",
                Long.class,
                merchant);
        return amount == null ? 0 : amount;
    }

    private int paymentsSeenBy(UUID merchant) {
        Integer seen = jdbc.queryForObject(
                "select coalesce(max(payments_seen), 0) from merchant_baseline "
                        + "where merchant_id = ?",
                Integer.class,
                merchant);
        return seen == null ? 0 : seen;
    }

    private java.util.List<String> cardsSeenFor(UUID merchant) {
        return jdbc.queryForList(
                "select distinct card_fingerprint from observed_payment where merchant_id = ? "
                        + "and outcome = 'APPROVED' and card_fingerprint is not null",
                String.class,
                merchant);
    }

    private int declinesSeenFor(UUID merchant) {
        Integer counted = jdbc.queryForObject(
                "select count(*) from observed_payment where merchant_id = ? "
                        + "and outcome = 'DECLINED'",
                Integer.class,
                merchant);
        return counted == null ? 0 : counted;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static String urlOf(ConfigurableApplicationContext service) {
        return "http://localhost:" + service.getEnvironment().getProperty("local.server.port");
    }
}
