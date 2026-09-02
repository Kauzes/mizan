package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * What happens after the acquirer stops answering.
 *
 * <p>A timed out call has not failed. It has stopped saying what happened, and the two things
 * it might have been are indistinguishable from here: the money may be reserved, or nothing
 * may have happened at all. So nothing is assumed, and the acquirer is asked.
 *
 * <p>The slow cards make both answers reachable, which is what stops a resolver that always
 * guessed "approved" from passing.
 */
@SpringBootTest(
        properties = {
            "mizan.acquirer.timeout=1s",
            // The sweep runs quickly here so a test can watch it work, and waits almost no
            // time first, because nothing else is competing for these payments.
            "mizan.acquirer.resolve-every=300ms",
            "mizan.acquirer.resolve-after=0s"
        })
class UnknownOutcomeTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String SLOW_APPROVE = "4000000000000069";
    private static final String SLOW_DECLINE = "4000000000000068";

    private static ConfigurableApplicationContext acquirer;

    @BeforeAll
    static void startTheAcquirer() {
        acquirer = new SpringApplicationBuilder(
                        dev.kauzes.mizan.banksim.BankSimulatorApplication.class)
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthorizationResolver resolver;

    @Autowired
    private UnknownOutcomes unknownOutcomes;

    @Test
    void aTimeoutSaysNobodyKnows() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);

        authorize(merchant, payment, SLOW_APPROVE).andExpect(status().isGatewayTimeout());

        assertThat(statusOf(payment))
                .as("not declined, which would be a lie, and not authorized, which would be a guess")
                .isEqualTo("AUTHORIZATION_UNKNOWN");
        assertThat(historyOf(payment))
                .as("and the step is recorded with why")
                .contains("AUTHORIZATION_UNKNOWN");
    }

    @Test
    void anUnknownOutcomeThatWasApprovedBecomesAuthorized() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);
        authorize(merchant, payment, SLOW_APPROVE).andExpect(status().isGatewayTimeout());

        resolver.resolve(merchant.id, payment);

        assertThat(statusOf(payment)).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject(
                        "select acquirer_reference from payment where id = ?",
                        String.class,
                        payment))
                .as("and it now holds the reference it never heard the first time")
                .isNotNull();
    }

    @Test
    void anUnknownOutcomeThatWasDeclinedBecomesDeclined() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);
        authorize(merchant, payment, SLOW_DECLINE).andExpect(status().isGatewayTimeout());

        resolver.resolve(merchant.id, payment);

        // The case that catches a resolver which assumes a timeout means approval.
        assertThat(statusOf(payment)).isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject(
                        "select decline_reason from payment where id = ?", String.class, payment))
                .isEqualTo("do_not_honour");
    }

    @Test
    void anOutcomeTheAcquirerHasNoRecordOfStaysUnknown() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);

        // The request never reached the acquirer. From here that is indistinguishable from a
        // lost reply, and only asking tells them apart.
        unknownOutcomes.record(merchant.id, payment, "the acquirer never answered");
        assertThat(statusOf(payment)).isEqualTo("AUTHORIZATION_UNKNOWN");

        resolver.resolve(merchant.id, payment);

        assertThat(statusOf(payment))
                .as("nothing happened, so nothing is claimed, and it is visible as unresolved")
                .isEqualTo("AUTHORIZATION_UNKNOWN");
    }

    @Test
    void aPaymentTheAcquirerNeverSawCanSimplyBeAttemptedAgain() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);
        unknownOutcomes.record(merchant.id, payment, "the acquirer never answered");
        resolver.resolve(merchant.id, payment);

        authorize(merchant, payment, "4000000000000000").andExpect(status().isOk());

        assertThat(statusOf(payment)).isEqualTo("AUTHORIZED");
    }

    @Test
    void resolvingTwiceDecidesOnce() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);
        authorize(merchant, payment, SLOW_APPROVE).andExpect(status().isGatewayTimeout());

        resolver.resolve(merchant.id, payment);
        resolver.resolve(merchant.id, payment);
        resolver.resolve(merchant.id, payment);

        assertThat(statusOf(payment)).isEqualTo("AUTHORIZED");
        assertThat(transitionsTo(payment, "AUTHORIZED"))
                .as("asking again is harmless; answering again would not be")
                .isEqualTo(1L);
    }

    @Test
    void resolvingAPaymentThatIsAlreadyDecidedChangesNothing() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);
        authorize(merchant, payment, "4000000000000000").andExpect(status().isOk());

        // The sweep can reach a payment the original call resolved a moment earlier.
        resolver.resolve(merchant.id, payment);

        assertThat(statusOf(payment)).isEqualTo("AUTHORIZED");
        assertThat(transitionsTo(payment, "AUTHORIZED")).isEqualTo(1L);
    }

    @Test
    @Timeout(60)
    void nobodyHasToAskForAPaymentToBeResolved() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);
        authorize(merchant, payment, SLOW_APPROVE).andExpect(status().isGatewayTimeout());

        // No call to the resolver here. A merchant should not have to notice that their
        // payment is in limbo, so the sweep finds it.
        assertThat(becomes(payment, "AUTHORIZED", Duration.ofSeconds(30)))
                .as("the sweep should resolve it without being told to")
                .isTrue();
    }

    private boolean becomes(UUID payment, String expected, Duration patience) throws Exception {
        long giveUpAt = System.nanoTime() + patience.toNanos();
        while (System.nanoTime() < giveUpAt) {
            if (expected.equals(statusOf(payment))) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private String statusOf(UUID payment) {
        return jdbc.queryForObject("select status from payment where id = ?", String.class, payment);
    }

    private String historyOf(UUID payment) {
        return jdbc.queryForList(
                        "select to_status from payment_transition where payment_id = ? order by at",
                        String.class,
                        payment)
                .toString();
    }

    private long transitionsTo(UUID payment, String status) {
        Long counted = jdbc.queryForObject(
                "select count(*) from payment_transition where payment_id = ? and to_status = ?",
                Long.class,
                payment,
                status);
        return counted == null ? 0 : counted;
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
}
