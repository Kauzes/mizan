package dev.kauzes.mizan.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * How business is, counted by the database.
 *
 * <p>Written against rows inserted directly, because what is being tested is the arithmetic of
 * the answer rather than the flow that produces it — and reaching every one of these states
 * through the API would mean an acquirer, a ledger and a scorer for a test about counting.
 *
 * <p>The cases that matter are the ones where a naive count is wrong: an intent nobody
 * authorized is not a refusal, a rate with no denominator is not zero, money in two currencies
 * does not add up, and something held three weeks ago is still held today.
 */
@SpringBootTest
class SummaryTest extends MizanIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void countsWhatWasTriedAndWhatWentThrough() throws Exception {
        UUID merchant = UUID.randomUUID();
        payment(merchant, PaymentStatus.CAPTURED, 100_00, "TRY", "auth_1", null, daysAgo(1));
        payment(merchant, PaymentStatus.AUTHORIZED, 50_00, "TRY", "auth_2", null, daysAgo(1));
        payment(merchant, PaymentStatus.VOIDED, 20_00, "TRY", "auth_3", null, daysAgo(1));
        payment(merchant, PaymentStatus.DECLINED, 30_00, "TRY", "auth_4", "insufficient_funds",
                daysAgo(1));
        // An intent nobody ever authorized. Not a refusal: counting it as one makes a merchant
        // who creates intents speculatively look like a merchant with a problem.
        payment(merchant, PaymentStatus.CREATED, 10_00, "TRY", null, null, daysAgo(1));

        summary(merchant)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.created").value(5))
                .andExpect(jsonPath("$.totals.attempted").value(4))
                .andExpect(jsonPath("$.totals.authorized").value(3))
                .andExpect(jsonPath("$.totals.captured").value(1))
                .andExpect(jsonPath("$.totals.declinedByAcquirer").value(1))
                .andExpect(jsonPath("$.authorizationRate").value(0.75));
    }

    @Test
    void tellsNoPaymentsApartFromEveryPaymentFailing() throws Exception {
        UUID quiet = UUID.randomUUID();

        // A rate of zero says every payment failed. No payments at all says something else,
        // and a dashboard showing 0% to a merchant who took the day off is lying to them.
        summary(quiet)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationRate").doesNotExist())
                .andExpect(jsonPath("$.totals.attempted").value(0))
                .andExpect(jsonPath("$.byDay.length()").value(0));
    }

    @Test
    void splitsRefusalsBetweenTheAcquirerAndThisPlatform() throws Exception {
        UUID merchant = UUID.randomUUID();
        payment(merchant, PaymentStatus.DECLINED, 10_00, "TRY", "auth_1", "insufficient_funds",
                daysAgo(1));
        payment(merchant, PaymentStatus.DECLINED, 10_00, "TRY", "auth_2", "insufficient_funds",
                daysAgo(1));
        payment(merchant, PaymentStatus.DECLINED, 10_00, "TRY", null,
                "This payment was refused: the amount is unusual", daysAgo(1));

        // Different problems with different fixes. A single failed count hides both.
        summary(merchant)
                .andExpect(jsonPath("$.refusals.length()").value(2))
                .andExpect(jsonPath("$.refusals[0].by").value("ACQUIRER"))
                .andExpect(jsonPath("$.refusals[0].reason").value("insufficient_funds"))
                .andExpect(jsonPath("$.refusals[0].payments").value(2))
                .andExpect(jsonPath("$.refusals[1].by").value("PLATFORM"));
    }

    @Test
    void neverAddsOneCurrencyToAnother() throws Exception {
        UUID merchant = UUID.randomUUID();
        payment(merchant, PaymentStatus.CAPTURED, 125_000, "TRY", "auth_1", null, daysAgo(1));
        payment(merchant, PaymentStatus.CAPTURED, 1_250, "JPY", "auth_2", null, daysAgo(1));

        // Adding lira to yen produces a number with no meaning, and a dashboard that did it
        // would be believed.
        summary(merchant)
                .andExpect(jsonPath("$.volume.length()").value(2))
                .andExpect(jsonPath("$.volume[?(@.currency == 'TRY')].captured").value(125_000))
                .andExpect(jsonPath("$.volume[?(@.currency == 'JPY')].captured").value(1_250));
    }

    @Test
    void putsTheVolumeBesideEachRate() throws Exception {
        UUID merchant = UUID.randomUUID();
        payment(merchant, PaymentStatus.CAPTURED, 100_00, "TRY", "auth_1", null, daysAgo(2));
        payment(merchant, PaymentStatus.CAPTURED, 200_00, "TRY", "auth_2", null, daysAgo(2));
        payment(merchant, PaymentStatus.DECLINED, 300_00, "TRY", "auth_3", "no", daysAgo(1));

        // A rate without a denominator is a rumour: ninety percent of ten payments and ninety
        // percent of ten thousand are different facts.
        summary(merchant)
                .andExpect(jsonPath("$.byDay.length()").value(2))
                .andExpect(jsonPath("$.byDay[0].attempted").value(2))
                .andExpect(jsonPath("$.byDay[0].captured").value(2))
                .andExpect(jsonPath("$.byDay[0].volume[0].captured").value(300_00))
                .andExpect(jsonPath("$.byDay[1].attempted").value(1))
                .andExpect(jsonPath("$.byDay[1].declined").value(1));
    }

    @Test
    void countsDaysInTheMerchantsOwnZone() throws Exception {
        UUID merchant = UUID.randomUUID();
        // Half past ten at night in Istanbul on the first, which is half past seven in UTC.
        payment(merchant, PaymentStatus.CAPTURED, 100_00, "TRY", "auth_1", null,
                Instant.parse("2026-03-01T19:30:00Z"));
        // And half past one in the morning on the second there, which is UTC's first still.
        payment(merchant, PaymentStatus.CAPTURED, 100_00, "TRY", "auth_2", null,
                Instant.parse("2026-03-01T22:30:00Z"));

        mockMvc.perform(get(summaryOf(merchant)
                                + "?from=2026-03-01T00:00:00Z&to=2026-03-03T00:00:00Z"
                                + "&zone=Europe/Istanbul")
                        .with(reader(merchant)))
                .andExpect(status().isOk())
                // Two days there, one day in UTC. A merchant asking about Tuesday means theirs.
                .andExpect(jsonPath("$.byDay.length()").value(2))
                .andExpect(jsonPath("$.byDay[0].day").value("2026-03-01"))
                .andExpect(jsonPath("$.byDay[1].day").value("2026-03-02"));
    }

    @Test
    void refusesAZoneThatIsNotOne() throws Exception {
        mockMvc.perform(get(summaryOf(UUID.randomUUID()) + "?zone=Middle/Earth")
                        .with(reader(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        UUID merchant = UUID.randomUUID();
        mockMvc.perform(get(summaryOf(merchant) + "?zone=Middle/Earth").with(reader(merchant)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("no time zone called")));
    }

    @Test
    void saysWhatIsOutstandingWhateverTheRangeAskedAbout() throws Exception {
        UUID merchant = UUID.randomUUID();
        payment(merchant, PaymentStatus.HELD_FOR_REVIEW, 500_00, "TRY", null, null, daysAgo(40));
        UUID stuck = payment(merchant, PaymentStatus.AUTHORIZATION_UNKNOWN, 10_00, "TRY", null,
                null, daysAgo(40));
        jdbc.update(
                "update payment set needs_attention_since = now(), attention_reason = 'no answer' "
                        + "where id = ?",
                stuck);

        // Asked about the last week, and told about something held six weeks ago. Nobody
        // should have to widen a date filter to discover that a customer is waiting.
        mockMvc.perform(get(summaryOf(merchant)
                                + "?from=" + daysAgo(7) + "&to=" + Instant.now())
                        .with(reader(merchant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.attempted").value(0))
                .andExpect(jsonPath("$.needsSomebody.waitingForAPerson").value(1))
                .andExpect(jsonPath("$.needsSomebody.needingAnOperator").value(1));
    }

    @Test
    void refusesARangeItWillNotAnswer() throws Exception {
        UUID merchant = UUID.randomUUID();

        mockMvc.perform(get(summaryOf(merchant)
                                + "?from=2026-03-02T00:00:00Z&to=2026-03-01T00:00:00Z")
                        .with(reader(merchant)))
                .andExpect(status().isUnprocessableContent());

        mockMvc.perform(get(summaryOf(merchant)
                                + "?from=2020-01-01T00:00:00Z&to=2026-01-01T00:00:00Z")
                        .with(reader(merchant)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("days at a time")));
    }

    @Test
    void neverCountsAnotherMerchantsPayments() throws Exception {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        payment(theirs, PaymentStatus.CAPTURED, 999_00, "TRY", "auth_1", null, daysAgo(1));

        summary(mine).andExpect(jsonPath("$.totals.created").value(0));
        mockMvc.perform(get(summaryOf(theirs)).with(reader(mine)))
                .andExpect(status().isForbidden());
    }

    // -- helpers ---------------------------------------------------------------------------

    private ResultActions summary(UUID merchant) throws Exception {
        return mockMvc.perform(get(summaryOf(merchant)).with(reader(merchant)));
    }

    private static String summaryOf(UUID merchant) {
        return "/api/v1/merchants/" + merchant + "/summary";
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor reader(
            UUID merchant) {
        return Callers.as(UUID.randomUUID(), merchant, Role.VIEWER);
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private UUID payment(
            UUID merchant,
            PaymentStatus status,
            long amount,
            String currency,
            String acquirerReference,
            String declineReason,
            Instant when) {

        UUID id = UUID.randomUUID();
        // The ledger entry and the held moment go with their statuses because the database
        // insists they agree: a captured payment names what recorded it, and a held one says
        // when. The constraints doing their job on a test that tried to write a state that
        // cannot happen.
        jdbc.update(
                """
                insert into payment (id, merchant_id, amount, currency, status, reference,
                    created_at, updated_at, version, acquirer_reference, decline_reason,
                    ledger_entry_id, held_at, refunded_amount, resolve_attempts)
                values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, 0, 0)
                """,
                id,
                merchant,
                amount,
                currency,
                status.name(),
                "order-" + id,
                Timestamp.from(when),
                Timestamp.from(when),
                // Unique per row: an acquirer reference is unique across the platform, which
                // is right and means a test cannot reuse "auth_1".
                acquirerReference == null ? null : acquirerReference + "-" + id,
                declineReason,
                status == PaymentStatus.CAPTURED ? UUID.randomUUID() : null,
                status == PaymentStatus.HELD_FOR_REVIEW ? Timestamp.from(when) : null);
        return id;
    }
}
