package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The first time a payment leaves the platform.
 *
 * <p>These run against the real simulator, started for the test, because the point of MIZ-42
 * was that outcomes can be provoked through the card rather than by telling a stub what to
 * say. A stub here would test that this service can talk to a stub.
 */
@SpringBootTest(properties = "mizan.acquirer.timeout=2s")
class AuthorizationTest extends MizanIntegrationTest {

    /** The acquirer, running for the length of this class on a port nothing else wants. */
    private static ConfigurableApplicationContext acquirer;

    @BeforeAll
    static void startTheAcquirer() {
        // Its own configuration file, because both services put an application.yml at the
        // root of the classpath and only one of them can win in a shared test JVM.
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

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String GOOD_CARD = "4000000000000000";
    private static final String NO_FUNDS = "4000000000000002";
    private static final String SLOW_CARD = "4000000000000069";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anApprovalMovesThePaymentAndKeepsTheAcquirersReference() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);

        authorize(merchant, payment, GOOD_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.acquirerReference").isNotEmpty())
                .andExpect(jsonPath("$.cardLastFour").value("0000"))
                .andExpect(jsonPath("$.declineReason").doesNotExist())
                .andExpect(jsonPath("$.allowedNext.length()").value(2));
    }

    @Test
    void anAuthorizationPostsNothingToTheBooks() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);

        authorize(merchant, payment, GOOD_CARD).andExpect(status().isOk());

        // An authorization is a promise that the money is there, not a movement of it, so
        // there is nothing in the ledger for this payment to point at. Capturing is what
        // makes an entry; a payment that was only authorized has none.
        assertThat(jdbc.queryForObject(
                        "select ledger_entry_id from payment where id = ?", Object.class, payment))
                .as("an authorized payment points at no entry, because none was written")
                .isNull();
    }

    @Test
    void aDeclineKeepsTheReasonTheAcquirerGave() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);

        authorize(merchant, payment, NO_FUNDS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineReason").value("insufficient_funds"))
                .andExpect(jsonPath("$.acquirerReference").isNotEmpty())
                .andExpect(jsonPath("$.allowedNext.length()")
                        .value(0));
    }

    @Test
    void aDeclinedPaymentCannotThenBeAuthorized() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);
        authorize(merchant, payment, NO_FUNDS).andExpect(status().isOk());

        authorize(merchant, payment, GOOD_CARD)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"))
                .andExpect(jsonPath("$.detail")
                        .value("A payment that is DECLINED cannot be authorized. That is where "
                                + "this payment ends."));
    }

    @Test
    void authorizingTwiceIsRefusedInTermsOfTheStateMachine() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);
        authorize(merchant, payment, GOOD_CARD).andExpect(status().isOk());

        authorize(merchant, payment, GOOD_CARD)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("A payment that is AUTHORIZED cannot be authorized. It can "
                                + "only become [CAPTURED, VOIDED]."));
    }

    @Test
    void aRetryWithTheSameKeyIsAnsweredWithTheFirstResult() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);
        String key = UUID.randomUUID().toString();

        String first = bodyOf(mockMvc.perform(authorizing(merchant, payment, GOOD_CARD)
                .with(Idempotently.key(key))));
        String second = bodyOf(mockMvc.perform(authorizing(merchant, payment, GOOD_CARD)
                .with(Idempotently.key(key))));

        assertThat(JSON.readTree(second))
                .as("a retry of an authorization is the same authorization")
                .isEqualTo(JSON.readTree(first));
    }

    @Test
    void noAnswerIsNotADecline() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);

        authorize(merchant, payment, SLOW_CARD)
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("UPSTREAM_TIMEOUT"))
                .andExpect(jsonPath("$.detail")
                        .value("The acquirer did not answer in time. Whether the payment was "
                                + "authorized is not yet known."));

        // And the payment says what is true: nobody knows. Not declined, which would be a
        // lie, and not authorized, which would be a guess. MIZ-44's resolver is what turns
        // this into an answer, by asking.
        assertThat(jdbc.queryForObject(
                        "select status from payment where id = ?", String.class, payment))
                .isEqualTo("AUTHORIZATION_UNKNOWN");
    }

    @Test
    void cannotAuthorizeAnotherMerchantsPayment() throws Exception {
        Merchant mine = merchant();
        Merchant theirs = merchant();
        UUID theirPayment = create(theirs);

        mockMvc.perform(authorizing(mine, theirPayment, GOOD_CARD)
                        .with(Idempotently.freshKey()))
                .andExpect(status().isNotFound());
    }

    @Test
    void readingIsNotAuthorizing() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);

        mockMvc.perform(post(payments(merchant) + "/" + payment + "/authorize")
                        .with(Callers.as(merchant.userId, merchant.id, Role.VIEWER))
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"card\":\"" + GOOD_CARD + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void neverKeepsTheCardItWasGiven() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);
        authorize(merchant, payment, GOOD_CARD).andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                        "select card_last_four from payment where id = ?", String.class, payment))
                .isEqualTo("0000");
        assertThat(jdbc.queryForList("select * from payment where id = ?", payment).toString())
                .as("the number itself is nowhere in the row")
                .doesNotContain(GOOD_CARD);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizing(
            Merchant merchant, UUID payment, String card) {

        return post(payments(merchant) + "/" + payment + "/authorize")
                .with(merchant.writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"card\":\"" + card + "\"}");
    }

    private ResultActions authorize(Merchant merchant, UUID payment, String card)
            throws Exception {

        return mockMvc.perform(authorizing(merchant, payment, card).with(Idempotently.freshKey()));
    }

    private UUID create(Merchant merchant) throws Exception {
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

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
