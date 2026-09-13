package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.banksim.BankSimulatorApplication;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The numbers that are about payments rather than about the JVM.
 *
 * <p>A metric that is always zero is indistinguishable from a platform where nothing is wrong,
 * so each of these drives the real thing and watches the number move. Real authorizations
 * against the real simulator, for the same reason the rest of this service's tests use it: a
 * stub would prove this service can count what a stub told it.
 *
 * <p>The last test is the one that matters most and is not about any single metric. Cardinality
 * is how a monitoring system falls over, and it falls over at the moment somebody needs it — so
 * no meter here may be tagged with a merchant, a payment or an amount.
 */
@SpringBootTest(properties = {
    "mizan.acquirer.timeout=2s",
    // Driven by hand. A count that a scheduler might or might not have refreshed is a test
    // about the scheduler.
    "mizan.metrics.count-first-after=3650d"
})
class PaymentMetricsTest extends MizanIntegrationTest {

    /** Tags that would let one merchant's business, or an unbounded set, into the metrics. */
    private static final Set<String> NEVER_A_LABEL = Set.of(
            "merchant", "merchantid", "merchant_id",
            "payment", "paymentid", "payment_id",
            "amount", "money", "reference", "card", "user", "userid");

    private static ConfigurableApplicationContext acquirer;

    @BeforeAll
    static void startTheAcquirer() {
        acquirer = new SpringApplicationBuilder(BankSimulatorApplication.class)
                .run("--spring.config.name=acquirer-test");
    }

    @AfterAll
    static void stopTheAcquirer() {
        if (acquirer != null) {
            acquirer.close();
        }
    }

    @DynamicPropertySource
    static void pointAtTheAcquirer(DynamicPropertyRegistry registry) {
        registry.add(
                "mizan.acquirer.base-url",
                () -> "http://localhost:"
                        + acquirer.getEnvironment().getProperty("local.server.port"));
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String GOOD_CARD = "4000000000000000";
    private static final String NO_FUNDS = "4000000000000002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private PaymentMetrics metrics;

    @Test
    void countsAnApproval() throws Exception {
        double before = authorizations("approved", "none");

        Merchant merchant = merchant();
        authorize(merchant, create(merchant), GOOD_CARD).andExpect(status().isOk());

        assertThat(authorizations("approved", "none")).isEqualTo(before + 1);
    }

    @Test
    void countsADeclineUnderTheReasonTheBankGave() throws Exception {
        double before = authorizations("declined", "insufficient_funds");

        Merchant merchant = merchant();
        authorize(merchant, create(merchant), NO_FUNDS).andExpect(status().isOk());

        // The reason, not a total. "Declines are up" is a fact somebody can do nothing with;
        // "declines for insufficient funds are up" is a conversation with a merchant, and
        // "declines for do not honour are up" is a conversation with an acquirer.
        assertThat(authorizations("declined", "insufficient_funds")).isEqualTo(before + 1);
    }

    @Test
    void countsAReasonItDoesNotRecogniseAsOther() {
        double before = authorizations("declined", "other");

        // An acquirer can put anything in that field. A metric that accepted whatever arrived
        // would let somebody else's integration decide how many series this platform keeps.
        metrics.declined("a_reason_no_acquirer_has_ever_sent");

        assertThat(authorizations("declined", "other")).isEqualTo(before + 1);
    }

    @Test
    void countsWhatIsWaitingForAPerson() {
        metrics.countWhatIsWaiting();

        // Zero or more, and the point is that the gauges exist and are read from the tables
        // rather than from something this service remembers. What they are is another test's
        // business: StuckPaymentsTest and ReviewTest own those.
        assertThat(gauge("mizan.payments.needing.a.person")).isNotNegative();
        assertThat(gauge("mizan.reviews.waiting")).isNotNegative();
    }

    @Test
    void countsAPaymentHeldForReviewSeparatelyFromADecline() {
        double declined = authorizations("declined", "none");
        double held = authorizations("held", "none");

        metrics.heldForReview();

        // A decline is between a merchant and a bank. A hold is this platform's own decision,
        // which somebody here has to answer for, and adding them together would hide the one
        // of the two anybody can act on.
        assertThat(authorizations("held", "none")).isEqualTo(held + 1);
        assertThat(authorizations("declined", "none")).isEqualTo(declined);
    }

    @Test
    void neverLabelsAMetricWithAMerchantAPaymentOrAnAmount() throws Exception {
        Merchant merchant = merchant();
        authorize(merchant, create(merchant), GOOD_CARD).andExpect(status().isOk());
        authorize(merchant, create(merchant), NO_FUNDS).andExpect(status().isOk());
        metrics.countWhatIsWaiting();

        List<Tag> offending = meters.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .filter(tag -> NEVER_A_LABEL.contains(
                        tag.getKey().toLowerCase(Locale.ROOT)))
                .toList();

        // Every label multiplies the series a monitoring system keeps, and a merchant id has
        // no upper bound. It is also one merchant's business in a system with no access
        // control on it.
        assertThat(offending).as("no metric may be labelled with any of these").isEmpty();
    }

    @Test
    void keepsTheReasonsToASetSomebodyChose() throws Exception {
        Merchant merchant = merchant();
        authorize(merchant, create(merchant), NO_FUNDS).andExpect(status().isOk());
        metrics.declined("something_new_from_the_acquirer");

        List<String> reasons = meters.find("mizan.payments.authorizations").counters().stream()
                .map(counter -> counter.getId().getTag(PaymentMetrics.REASON))
                .distinct()
                .toList();

        // Bounded by construction: what an acquirer sends is either one of the names this
        // platform knows or "other". The specific reason is still on the payment and in the
        // log, where one row is one event rather than a dimension.
        assertThat(reasons).isNotEmpty().allSatisfy(reason -> assertThat(reason).isLowerCase());
        assertThat(reasons).contains("other");
    }

    private double authorizations(String outcome, String reason) {
        return meters.find("mizan.payments.authorizations")
                .tag(PaymentMetrics.OUTCOME, outcome)
                .tag(PaymentMetrics.REASON, reason)
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private double gauge(String name) {
        return meters.find(name).gauges().stream().mapToDouble(Gauge::value).sum();
    }

    private ResultActions authorize(Merchant merchant, UUID payment, String card)
            throws Exception {

        return mockMvc.perform(post(payments(merchant) + "/" + payment + "/authorize")
                .with(merchant.writer())
                .with(Idempotently.freshKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"card\":\"" + card + "\"}"));
    }

    private UUID create(Merchant merchant) throws Exception {
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

    private static String payments(Merchant merchant) {
        return "/api/v1/merchants/" + merchant.id() + "/payments";
    }

    private record Merchant(UUID id, UUID userId) {

        RequestPostProcessor writer() {
            return Callers.as(userId, id, Role.ADMIN);
        }
    }

    private static Merchant merchant() {
        return new Merchant(UUID.randomUUID(), UUID.randomUUID());
    }
}
