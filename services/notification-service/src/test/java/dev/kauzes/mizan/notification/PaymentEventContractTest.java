package dev.kauzes.mizan.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.time.Duration;
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
 * The wire between two services that are developed together.
 *
 * <p>{@link PaymentNotificationsTest} publishes messages this test suite wrote. They are the
 * right shape today because somebody copied it, and nothing would tell anybody the day the
 * payment service changed it — which is the failure most worth catching between two services
 * in one repository, and the one a hand-written fixture cannot catch by construction.
 *
 * <p>So this one starts the real payment service, with the real acquirer and the real ledger
 * behind it, takes a payment through it, and waits for this service to say something about it.
 * Nothing here knows what a payment event looks like. If the two ever stop agreeing, this
 * fails and the fixtures do not.
 */
@SpringBootTest
class PaymentEventContractTest extends MizanIntegrationTest {

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
                "spring.kafka.consumer.group-id", () -> "contract-test-" + UUID.randomUUID());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Timeout(180)
    void aPaymentCapturedByTheRealServiceIsSomethingThisServiceCanReadAndActOn() {
        UUID merchant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        openSettlementAccount(merchant, user);

        UUID payment = createPayment(merchant, user);
        authorize(merchant, user, payment);
        capture(merchant, user, payment);

        // Nothing in this test built a message. The payment service wrote the event, its relay
        // published it, and this service read it off the topic and decided what to say.
        Map<String, Object> notification = eventually(payment);
        assertThat(notification.get("kind")).isEqualTo("PAYMENT_CAPTURED");
        assertThat(notification.get("merchant_id")).hasToString(merchant.toString());
        assertThat(notification.get("message"))
                .asString()
                .startsWith("You have been paid 1250.00 TRY for order-");
    }

    // -- driving the real payment service ---------------------------------------------------

    private UUID createPayment(UUID merchant, UUID user) {
        String body = asMerchant(payments, merchant, user)
                .post()
                .uri("/api/v1/merchants/{merchantId}/payments", merchant)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"amount":125000,"currency":"TRY","reference":"order-%s"}
                        """.formatted(UUID.randomUUID()))
                .retrieve()
                .body(String.class);

        return UUID.fromString(JSON.readTree(body).path("id").asString());
    }

    private void authorize(UUID merchant, UUID user, UUID payment) {
        asMerchant(payments, merchant, user)
                .post()
                .uri(
                        "/api/v1/merchants/{merchantId}/payments/{paymentId}/authorize",
                        merchant,
                        payment)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"card\":\"" + GOOD_CARD + "\"}")
                .retrieve()
                .toBodilessEntity();
    }

    private void capture(UUID merchant, UUID user, UUID payment) {
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

    /**
     * The headers the gateway would have set, since there is no gateway in this JVM. Every
     * service behind it trusts these, which is only safe because the gateway strips whatever
     * arrived under the same names.
     */
    private static RestClient asMerchant(
            ConfigurableApplicationContext service, UUID merchant, UUID user) {

        return RestClient.builder()
                .baseUrl(urlOf(service))
                .defaultHeader(CallerIdentity.USER_HEADER, user.toString())
                .defaultHeader(CallerIdentity.MERCHANT_HEADER, merchant.toString())
                .defaultHeader(CallerIdentity.ROLES_HEADER, Role.ADMIN.name())
                .build();
    }

    private Map<String, Object> eventually(UUID payment) {
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < giveUpAt) {
            List<Map<String, Object>> found =
                    jdbc.queryForList("select * from notification where payment_id = ?", payment);
            if (!found.isEmpty()) {
                return found.getFirst();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError(
                "the payment service captured " + payment + " and nothing was ever said about it");
    }

    private static String urlOf(ConfigurableApplicationContext service) {
        return "http://localhost:" + service.getEnvironment().getProperty("local.server.port");
    }
}
