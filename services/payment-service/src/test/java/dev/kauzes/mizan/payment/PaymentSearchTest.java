package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finding one payment among a merchant's many.
 *
 * <p>The endpoint this replaces returned every payment a merchant had ever taken, which worked
 * only because no merchant here has many yet. So the tests that matter are the ones about the
 * bounds — that a page is a page, that somebody cannot ask for the whole table, and that the
 * tenant boundary is not one filter among several — rather than about any particular filter.
 */
@SpringBootTest
class PaymentSearchTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void narrowsByStatus() throws Exception {
        Merchant merchant = merchant();
        UUID created = payment(merchant, 100_00);
        UUID declined = payment(merchant, 200_00);
        put(declined, PaymentStatus.DECLINED, null);

        search(merchant, "?status=DECLINED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(declined.toString()));

        // Two statuses widen against each other and narrow against everything else.
        search(merchant, "?status=DECLINED&status=CREATED")
                .andExpect(jsonPath("$.length()").value(2));

        search(merchant, "?status=CREATED")
                .andExpect(jsonPath("$[0].id").value(created.toString()));
    }

    @Test
    void narrowsByAmountAndByWhatRiskDecided() throws Exception {
        Merchant merchant = merchant();
        UUID small = payment(merchant, 10_00);
        UUID large = payment(merchant, 900_00);
        put(large, PaymentStatus.HELD_FOR_REVIEW, "REVIEW");

        search(merchant, "?minAmount=50000")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(large.toString()));

        search(merchant, "?maxAmount=50000")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(small.toString()));

        // Inclusive at both ends, so a merchant asking for exactly what they were charged
        // finds it rather than concluding the platform lost it.
        search(merchant, "?minAmount=1000&maxAmount=1000")
                .andExpect(jsonPath("$.length()").value(1));

        search(merchant, "?riskVerdict=REVIEW")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(large.toString()));
    }

    @Test
    void narrowsByWhenAndTreatsTheEndAsExclusive() throws Exception {
        Merchant merchant = merchant();
        Instant midnight = Instant.parse("2026-03-01T00:00:00Z");
        UUID onTheFirst = payment(merchant, 1_00);
        UUID atMidnight = payment(merchant, 2_00);
        createdAt(onTheFirst, midnight.plus(6, ChronoUnit.HOURS));
        createdAt(atMidnight, midnight.plus(1, ChronoUnit.DAYS));

        // Asking for the first of March twice in a row must not show the midnight payment on
        // both days, which is what an inclusive end does.
        search(merchant, "?from=2026-03-01T00:00:00Z&to=2026-03-02T00:00:00Z")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(onTheFirst.toString()));

        search(merchant, "?from=2026-03-02T00:00:00Z&to=2026-03-03T00:00:00Z")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(atMidnight.toString()));
    }

    @Test
    void findsAPaymentByTheMerchantsOwnName() throws Exception {
        Merchant merchant = merchant();
        String reference = "order-" + UUID.randomUUID();
        UUID wanted = payment(merchant, 5_00, reference);
        payment(merchant, 5_00, reference + "-0");

        // Exactly, not as a prefix: "order-1" must not answer with order-1, order-10 and
        // order-100, which is the difference between finding a payment and being shown a page.
        search(merchant, "?reference=" + reference)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(wanted.toString()));
    }

    @Test
    void pagesNewestFirstAndSaysWhereThePageSits() throws Exception {
        Merchant merchant = merchant();
        for (int i = 0; i < 7; i++) {
            payment(merchant, 1_00 + i);
        }

        search(merchant, "?size=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(header().string("X-Total-Count", "7"))
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "3"))
                .andExpect(header().string("Link", org.hamcrest.Matchers.containsString("rel=\"next\"")))
                .andExpect(header().string("Link", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("rel=\"prev\""))));

        search(merchant, "?size=3&page=2")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(header().string("Link", org.hamcrest.Matchers.containsString("rel=\"prev\"")))
                .andExpect(header().string("Link", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("rel=\"next\""))));
    }

    @Test
    void theBodyIsStillTheListItAlwaysWas() throws Exception {
        Merchant merchant = merchant();
        payment(merchant, 1_00);

        // The paging went into headers rather than into an envelope on purpose. An envelope
        // is tidier to write and breaks every client parsing an array, for a contract this
        // platform publishes and asks people to build against.
        String body = bodyOf(search(merchant, ""));
        assertThat(JSON.readTree(body).isArray()).isTrue();
        assertThat(JSON.readTree(body).get(0).path("status").asString()).isEqualTo("CREATED");
    }

    @Test
    void refusesAPageBiggerThanTheAnswerIsAllowedToBe() throws Exception {
        Merchant merchant = merchant();

        search(merchant, "?size=201")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
        search(merchant, "?size=0").andExpect(status().isUnprocessableContent());
        search(merchant, "?page=-1").andExpect(status().isUnprocessableContent());
    }

    @Test
    void refusesToPageFurtherInThanItCanAfford() throws Exception {
        Merchant merchant = merchant();

        // An offset is read by counting past every row before it, so this is not a limit on
        // what a merchant may see: it is a refusal to read a million rows to skip them.
        search(merchant, "?page=500&size=50")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("narrow the search")));
    }

    @Test
    void refusesAFilterItDoesNotUnderstandRatherThanIgnoringIt() throws Exception {
        Merchant merchant = merchant();

        // Silently ignoring an unknown filter widens the answer, and a merchant looking for
        // one payment is shown a page of others and concludes the platform lost it.
        search(merchant, "?status=NEARLY")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("no payment status called")));
        search(merchant, "?riskVerdict=MAYBE").andExpect(status().isUnprocessableContent());
        search(merchant, "?minAmount=900&maxAmount=100")
                .andExpect(status().isUnprocessableContent());
        search(merchant, "?from=2026-03-02T00:00:00Z&to=2026-03-01T00:00:00Z")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void neverReachesAnotherMerchantHoweverItIsAsked() throws Exception {
        Merchant mine = merchant();
        Merchant theirs = merchant();
        payment(theirs, 42_00, "not-mine");

        // The merchant is the first condition rather than one of several, so there is no
        // combination of the others that leaves it off.
        search(mine, "?reference=not-mine").andExpect(jsonPath("$.length()").value(0));
        search(mine, "?minAmount=0&maxAmount=99999999").andExpect(jsonPath("$.length()").value(0));

        // And asking about somebody else's merchant directly is refused before any of this.
        mockMvc.perform(get(payments(theirs)).with(mine.reader()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEmptyAnswerIsAnEmptyAnswerRatherThanAnError() throws Exception {
        Merchant merchant = merchant();
        payment(merchant, 1_00);

        search(merchant, "?status=CAPTURED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0))
                .andExpect(header().string("X-Total-Count", "0"));
    }

    // -- helpers ---------------------------------------------------------------------------

    private ResultActions search(Merchant merchant, String query) throws Exception {
        return mockMvc.perform(get(payments(merchant) + query).with(merchant.reader()));
    }

    private UUID payment(Merchant merchant, long amount) throws Exception {
        return payment(merchant, amount, "order-" + UUID.randomUUID());
    }

    private UUID payment(Merchant merchant, long amount, String reference) throws Exception {
        String body = mockMvc.perform(post(payments(merchant))
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":%d,"currency":"TRY","reference":"%s"}
                                """.formatted(amount, reference)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        return UUID.fromString(JSON.readTree(body).path("id").asString());
    }

    /**
     * Put a payment into a state the API cannot reach without an acquirer.
     *
     * <p>held_at goes with the status because the database insists the two agree, which is
     * the constraint doing its job on a test that tried to write a state that cannot happen.
     */
    private void put(UUID payment, PaymentStatus status, String verdict) {
        jdbc.update(
                "update payment set status = ?, risk_verdict = ?, held_at = ? where id = ?",
                status.name(),
                verdict,
                status == PaymentStatus.HELD_FOR_REVIEW ? Timestamp.from(Instant.now()) : null,
                payment);
    }

    private void createdAt(UUID payment, Instant when) {
        jdbc.update(
                "update payment set created_at = ? where id = ?", Timestamp.from(when), payment);
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private record Merchant(UUID id, UUID userId) {

        RequestPostProcessor writer() {
            return Callers.as(userId, id, Role.ADMIN);
        }

        RequestPostProcessor reader() {
            return Callers.as(userId, id, Role.VIEWER);
        }
    }

    private static Merchant merchant() {
        return new Merchant(UUID.randomUUID(), UUID.randomUUID());
    }

    private static String payments(Merchant merchant) {
        return "/api/v1/merchants/" + merchant.id + "/payments";
    }
}
