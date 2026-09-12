package dev.kauzes.mizan.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kauzes.mizan.common.identity.CallerIdentity;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.MizanContainers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Taking the fee and paying the merchant, against the real ledger.
 *
 * <p>The real ledger on purpose. What is being tested is that two movements sum to zero, that
 * they name accounts the ledger will accept, and that the merchant's balance ends where it
 * should — and a fake ledger would agree with whatever this service asked it for. The one
 * assertion that matters is the last one: after a fee and a payout, the platform owes this
 * merchant nothing for that batch.
 */
@SpringBootTest(properties = {
    "mizan.settlement.close-every=3650d",
    "mizan.settlement.fee-basis-points=290",
    "mizan.settlement.fee-fixed-per-payment=30",
    "mizan.internal.service-token=a-service-token-for-tests"
})
class PayoutTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SERVICE_TOKEN = "a-service-token-for-tests";
    private static final LocalDate TUESDAY = LocalDate.of(2026, 3, 3);

    private static ConfigurableApplicationContext ledger;

    @BeforeAll
    static void startTheLedger() {
        ledger = new SpringApplicationBuilder(dev.kauzes.mizan.ledger.LedgerApplication.class)
                .run(
                        "--spring.config.name=ledger-test",
                        "--spring.datasource.url=" + MizanContainers.database("ledger"),
                        "--spring.datasource.username=" + MizanContainers.postgres().getUsername(),
                        "--spring.datasource.password=" + MizanContainers.postgres().getPassword(),
                        "--mizan.internal.service-token=" + SERVICE_TOKEN);
    }

    @AfterAll
    static void stopIt() {
        if (ledger != null) {
            ledger.close();
        }
    }

    @DynamicPropertySource
    static void pointAtIt(DynamicPropertyRegistry registry) {
        registry.add("mizan.ledger.base-url", () -> urlOf(ledger));
        registry.add(
                "spring.kafka.consumer.group-id", () -> "settlement-payout-" + UUID.randomUUID());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Settlement settlement;

    @Autowired
    private Payouts payouts;

    @Test
    void theFeeLandsInThePlatformsOwnAccount() {
        UUID merchant = merchantWithAnAccount();
        captured(merchant, 100_00, TUESDAY);
        Settlement.Closed closed = settlement.close(merchant, TUESDAY, "TRY");

        payouts.recordFeeFor(closed.batchId());

        // Revenue that is not written down is revenue nobody can reconcile, which is why the
        // fee has an account rather than simply being subtracted from what is owed.
        Map<String, Object> batch = batch(closed.batchId());
        assertThat(batch.get("fee_entry_id")).isNotNull();

        assertThat(balanceOf(merchant, "settlement.try"))
                .as("the merchant is owed the fee less than was captured")
                .isEqualTo(-(100_00L - closed.fee().amount()));
    }

    @Test
    void payingAMerchantLeavesThemOwedNothingForThatBatch() {
        UUID merchant = merchantWithAnAccount();
        captured(merchant, 100_00, TUESDAY);
        captured(merchant, 200_00, TUESDAY);
        Settlement.Closed closed = settlement.close(merchant, TUESDAY, "TRY");

        payouts.pay(closed.batchId());

        // The one assertion that matters. Everything captured has either been charged as a
        // fee or paid to the merchant, and the balance says so rather than a column.
        assertThat(balanceOf(merchant, "settlement.try"))
                .as("nothing is owed for this batch any more")
                .isZero();
        assertThat(batch(closed.batchId()).get("paid_at")).isNotNull();
    }

    @Test
    void andTheTwoMovementsAreEntriesRatherThanAdjustments() {
        UUID merchant = merchantWithAnAccount();
        captured(merchant, 100_00, TUESDAY);
        Settlement.Closed closed = settlement.close(merchant, TUESDAY, "TRY");
        payouts.pay(closed.batchId());

        // Entries, each with postings summing to zero, rather than a column being adjusted.
        // A balance that can be adjusted is a balance nobody can audit, which is the whole
        // reason this platform has a ledger. Three in total here: the capture that put the
        // money there, and the two this story adds.
        List<Map<String, Object>> entries = entriesFor(merchant);
        assertThat(entries).hasSize(3);
        assertThat(entries)
                .extracting(entry -> entry.get("externalReference"))
                .contains(
                        "settlement:" + closed.batchId() + ":fee",
                        "settlement:" + closed.batchId() + ":payout");
    }

    @Test
    void payingTwicePaysOnce() {
        UUID merchant = merchantWithAnAccount();
        captured(merchant, 100_00, TUESDAY);
        Settlement.Closed closed = settlement.close(merchant, TUESDAY, "TRY");

        Map<String, Object> first = payouts.pay(closed.batchId());
        Map<String, Object> again = payouts.pay(closed.batchId());

        assertThat(first.get("paidNow")).isEqualTo(true);
        // Said out loud, because "paid" and "was already paid" are different things to an
        // operator wondering whether they double-clicked.
        assertThat(again.get("paidNow")).isEqualTo(false);
        assertThat(again.get("payout_entry_id")).isEqualTo(first.get("payout_entry_id"));

        assertThat(balanceOf(merchant, "settlement.try"))
                .as("and the merchant was not paid twice")
                .isZero();
        assertThat(entriesFor(merchant))
                .as("one capture, one fee, one payout, and no second payout")
                .hasSize(3);
    }

    @Test
    void recordsTheFeeBeforePayingEvenIfNobodyAskedForThat() {
        UUID merchant = merchantWithAnAccount();
        captured(merchant, 100_00, TUESDAY);
        Settlement.Closed closed = settlement.close(merchant, TUESDAY, "TRY");

        // Paying before the fee is recorded would leave the merchant's balance holding a fee
        // nobody had earned, and the two halves of a settlement visible in the wrong order to
        // anybody reading the books at that moment.
        payouts.pay(closed.batchId());

        Map<String, Object> batch = batch(closed.batchId());
        assertThat(batch.get("fee_entry_id")).isNotNull();
        assertThat(batch.get("payout_entry_id")).isNotNull();
    }

    @Test
    void finishesEveryBatchWhoseFeeIsNotInTheBooksYet() {
        UUID merchant = merchantWithAnAccount();
        captured(merchant, 10_00, TUESDAY.minusDays(2));
        captured(merchant, 20_00, TUESDAY.minusDays(1));
        settlement.close(merchant, TUESDAY.minusDays(2), "TRY");
        settlement.close(merchant, TUESDAY.minusDays(1), "TRY");

        // As if the ledger had been unreachable while both days closed. Every batch, not only
        // the most recent: an hour of unreachable ledger would otherwise leave an hour of
        // batches uncharged forever.
        jdbc.update("update settlement_batch set fee_entry_id = null, fee_recorded_at = null "
                + "where merchant_id = ?", merchant);

        assertThat(payouts.recordWhatIsNotYetInTheBooks()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from settlement_batch "
                                + "where merchant_id = ? and fee_entry_id is null",
                        Integer.class,
                        merchant))
                .isZero();
    }

    @Test
    void refusesToPayAMerchantWhoNeverOpenedAnAccountToBePaidInto() {
        // Deliberately no settlement account, so the capture is not in the books either:
        // there is nowhere to put it. The ledger opens none on anybody's behalf, and the
        // refusal has to say which account is missing rather than "internal error".
        UUID merchant = UUID.randomUUID();
        onlyHeardAbout(merchant, 100_00, TUESDAY);
        Settlement.Closed closed = settlement.close(merchant, TUESDAY, "TRY");

        assertThatThrownBy(() -> payouts.pay(closed.batchId()))
                .hasMessageContaining("settlement.try");

        assertThat(batch(closed.batchId()).get("paid_at"))
                .as("and nothing is recorded as paid")
                .isNull();
    }

    @Test
    void refusesToPayMoreThanTheBooksSayIsOwed() {
        UUID merchant = merchantWithAnAccount();
        captured(merchant, 100_00, TUESDAY);
        Settlement.Closed closed = settlement.close(merchant, TUESDAY, "TRY");

        // Money going back to a customer after the day closed. The batch still says what that
        // day's captures came to — refunds are not netted in — but what is owed has moved,
        // and a settlement service adding up only its own rows would pay out money that has
        // already gone back.
        refundedInTheBooks(merchant, 60_00);

        assertThatThrownBy(() -> payouts.pay(closed.batchId()))
                .hasMessageContaining("is owed to this merchant");

        assertThat(batch(closed.batchId()).get("paid_at"))
                .as("and nothing is recorded as paid")
                .isNull();
    }

    @Test
    void listsWhatIsOwedAndUnpaidOldestFirst() {
        UUID merchant = merchantWithAnAccount();
        captured(merchant, 10_00, TUESDAY.minusDays(1));
        captured(merchant, 20_00, TUESDAY);
        settlement.close(merchant, TUESDAY.minusDays(1), "TRY");
        Settlement.Closed second = settlement.close(merchant, TUESDAY, "TRY");

        List<Map<String, Object>> unpaid = payouts.unpaid(50);
        List<UUID> mine = unpaid.stream()
                .filter(batch -> merchant.equals(batch.get("merchant_id")))
                .map(batch -> (UUID) batch.get("id"))
                .toList();

        assertThat(mine).hasSize(2);
        assertThat(mine.getLast()).isEqualTo(second.batchId());
    }

    // -- helpers ---------------------------------------------------------------------------

    private UUID merchantWithAnAccount() {
        UUID merchant = UUID.randomUUID();
        asMerchant(merchant)
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
        return merchant;
    }

    /**
     * A capture, both as this service heard about it and as the books already hold it.
     *
     * <p>Both halves, because a settlement only means anything on top of a capture entry: the
     * platform holds the money at the acquirer and owes it to the merchant, and what this
     * story does is move that liability. A test that skipped the capture entry would be
     * asserting about a balance that started from nowhere.
     */
    private void captured(UUID merchant, long amount, LocalDate day) {
        UUID payment = UUID.randomUUID();
        asPlatform()
                .post()
                .uri("/internal/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"merchantId":"%s","externalReference":"payment:%s:capture",
                         "description":"Card payment captured","occurredAt":"%s",
                         "postings":[{"accountCode":"platform.clearing.try","amount":%d},
                                     {"accountCode":"settlement.try","amount":%d}]}
                        """.formatted(merchant, payment, Instant.now(), amount, -amount))
                .retrieve()
                .toBodilessEntity();

        jdbc.update(
                """
                insert into settleable (payment_id, merchant_id, amount, currency,
                    captured_at, settled_for, acquirer_reference)
                values (?, ?, ?, 'TRY', ?, ?, ?)
                """,
                payment,
                merchant,
                amount,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                day,
                "auth_" + payment);
    }

    /**
     * Money going back to a customer, as the payment service would record it.
     *
     * <p>The opposite of a capture: the merchant is owed less and the platform holds less.
     */
    private void refundedInTheBooks(UUID merchant, long amount) {
        UUID refund = UUID.randomUUID();
        asPlatform()
                .post()
                .uri("/internal/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"merchantId":"%s","externalReference":"refund:%s:live",
                         "description":"Card payment refunded","occurredAt":"%s",
                         "postings":[{"accountCode":"settlement.try","amount":%d},
                                     {"accountCode":"platform.clearing.try","amount":%d}]}
                        """.formatted(merchant, refund, Instant.now(), amount, -amount))
                .retrieve()
                .toBodilessEntity();
    }

    /** Heard about but not in the books, for the merchant who has nowhere to be paid. */
    private void onlyHeardAbout(UUID merchant, long amount, LocalDate day) {
        UUID payment = UUID.randomUUID();
        jdbc.update(
                """
                insert into settleable (payment_id, merchant_id, amount, currency,
                    captured_at, settled_for, acquirer_reference)
                values (?, ?, ?, 'TRY', ?, ?, ?)
                """,
                payment,
                merchant,
                amount,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                day,
                "auth_" + payment);
    }

    private Map<String, Object> batch(UUID batchId) {
        return jdbc.queryForMap("select * from settlement_batch where id = ?", batchId);
    }

    /** Asked of the ledger, because the ledger's own figure is the only one that counts. */
    private long balanceOf(UUID merchant, String code) {
        String accounts = asMerchant(merchant)
                .get()
                .uri("/api/v1/merchants/{merchantId}/accounts", merchant)
                .retrieve()
                .body(String.class);

        for (var account : JSON.readTree(accounts)) {
            if (code.equals(account.path("code").asString())) {
                return account.path("balance").asLong();
            }
        }
        throw new AssertionError("this merchant has no " + code);
    }

    private List<Map<String, Object>> entriesFor(UUID merchant) {
        String entries = asMerchant(merchant)
                .get()
                .uri("/api/v1/merchants/{merchantId}/entries", merchant)
                .retrieve()
                .body(String.class);

        List<Map<String, Object>> found = new java.util.ArrayList<>();
        for (var entry : JSON.readTree(entries)) {
            found.add(Map.of("externalReference", entry.path("externalReference").asString()));
        }
        return found;
    }

    /** The credential no merchant has, for the entries no merchant may write. */
    private static RestClient asPlatform() {
        return RestClient.builder()
                .baseUrl(urlOf(ledger))
                .defaultHeader(
                        dev.kauzes.mizan.common.identity.ServiceCredential.HEADER, SERVICE_TOKEN)
                .build();
    }

    private static RestClient asMerchant(UUID merchant) {
        return RestClient.builder()
                .baseUrl(urlOf(ledger))
                .defaultHeader(CallerIdentity.USER_HEADER, UUID.randomUUID().toString())
                .defaultHeader(CallerIdentity.MERCHANT_HEADER, merchant.toString())
                .defaultHeader(CallerIdentity.ROLES_HEADER, Role.ADMIN.name())
                .build();
    }

    private static String urlOf(ConfigurableApplicationContext service) {
        return "http://localhost:" + service.getEnvironment().getProperty("local.server.port");
    }
}
