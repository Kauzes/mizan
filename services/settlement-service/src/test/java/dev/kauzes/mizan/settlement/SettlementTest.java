package dev.kauzes.mizan.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Closing a day, and everything about it that has to still be true the second time.
 *
 * <p>Captures are written straight into the table this service keeps rather than driven
 * through Kafka, because what is being tested is the batch — the event path has its own test.
 * The cases that matter are the ones a naive close gets wrong: a second close making a second
 * batch, two currencies added together, and fees that do not add up to the fee.
 */
@SpringBootTest(properties = {
    // Driven by hand, so what has happened at each point is a fact rather than a race.
    "mizan.settlement.close-every=3650d",
    "mizan.settlement.fee-basis-points=290",
    "mizan.settlement.fee-fixed-per-payment=30"
})
class SettlementTest extends MizanIntegrationTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 3, 3);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Settlement settlement;

    @Autowired
    private ClosingDays closing;

    @Test
    void closesADayIntoOneBatchWithTheFeeTakenOut() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, 100_00, "TRY", TUESDAY);
        captured(merchant, 200_00, "TRY", TUESDAY);

        Settlement.Closed closed = settlement.close(merchant, TUESDAY, "TRY");

        assertThat(closed.captured().amount()).isEqualTo(300_00L);
        // 2.9% of 300.00, plus 0.30 a payment.
        assertThat(closed.fee().amount()).isEqualTo(930L);
        assertThat(closed.net().amount()).isEqualTo(300_00L - 930L);
        assertThat(closed.payments()).isEqualTo(2);
    }

    @Test
    void storesTheThreeFiguresRatherThanWorkingThemOutLater() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, 100_00, "TRY", TUESDAY);
        Settlement.Closed closed = settlement.close(merchant, TUESDAY, "TRY");

        Map<String, Object> row = jdbc.queryForMap(
                "select captured, fee, net, fee_basis_points, fee_fixed_per_payment "
                        + "from settlement_batch where id = ?",
                closed.batchId());

        // Including the rule as it was applied. Without it nobody can answer "why was I
        // charged this" about a batch from before the rule changed.
        assertThat(((Number) row.get("captured")).longValue()).isEqualTo(100_00L);
        assertThat(((Number) row.get("fee_basis_points")).intValue()).isEqualTo(290);
        assertThat(((Number) row.get("fee_fixed_per_payment")).longValue()).isEqualTo(30L);
    }

    @Test
    void theFeesOnThePaymentsAddUpToTheFeeOnTheBatch() {
        UUID merchant = UUID.randomUUID();
        for (long amount : new long[] {333, 333, 334, 1, 99_999}) {
            captured(merchant, amount, "TRY", TUESDAY);
        }

        Settlement.Closed closed = settlement.close(merchant, TUESDAY, "TRY");

        Long attributed = jdbc.queryForObject(
                "select sum(fee) from settleable where batch_id = ?", Long.class, closed.batchId());

        // The property the whole design turns on: a merchant adding up their own statement
        // gets the number they were charged.
        assertThat(attributed).isEqualTo(closed.fee().amount());
    }

    @Test
    void closingTwiceMakesOneBatch() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, 100_00, "TRY", TUESDAY);

        Settlement.Closed first = settlement.close(merchant, TUESDAY, "TRY");
        Settlement.Closed again = settlement.close(merchant, TUESDAY, "TRY");

        // The same batch, not a second one. A close nobody can repeat is a close nobody can
        // recover, and this is the one somebody runs by hand during an incident.
        assertThat(again.batchId()).isEqualTo(first.batchId());
        assertThat(batches(merchant)).isEqualTo(1);
    }

    @Test
    void aCaptureThatArrivesLateIsPaidInTheNextBatch() {
        UUID merchant = UUID.randomUUID();
        UUID onTime = captured(merchant, 100_00, "TRY", TUESDAY);
        Settlement.Closed tuesday = settlement.close(merchant, TUESDAY, "TRY");

        // Captures arrive as events, so one can turn up after its own day has been settled.
        // It must be paid rather than stranded, and the batch a merchant has already been
        // shown must not change — so it waits for the next close.
        UUID late = captured(merchant, 50_00, "TRY", TUESDAY);
        assertThat(settlement.close(merchant, TUESDAY, "TRY").batchId())
                .as("re-closing a settled day answers with its batch and claims nothing")
                .isEqualTo(tuesday.batchId());
        assertThat(batchOf(late)).isNull();

        Settlement.Closed wednesday = settlement.close(merchant, TUESDAY.plusDays(1), "TRY");

        assertThat(wednesday.batchId()).isNotEqualTo(tuesday.batchId());
        assertThat(wednesday.captured().amount()).isEqualTo(50_00L);
        assertThat(batchOf(late)).isEqualTo(wednesday.batchId());
        // And the one that was on time never moved.
        assertThat(batchOf(onTime)).isEqualTo(tuesday.batchId());
        // Its own capture date is still on the row, which is what the bank will name it by.
        assertThat(jdbc.queryForObject(
                        "select settled_for from settleable where payment_id = ?",
                        LocalDate.class,
                        late))
                .isEqualTo(TUESDAY);
    }

    @Test
    void neverAddsOneCurrencyToAnother() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, 100_00, "TRY", TUESDAY);
        captured(merchant, 1_250, "JPY", TUESDAY);

        List<Settlement.Closed> closed = settlement.closeDay(TUESDAY);

        // Two batches for one merchant on one day, because a total in two currencies is not a
        // total and somebody would be paid it.
        assertThat(closed).hasSize(2);
        assertThat(closed)
                .extracting(one -> one.captured().currency().getCurrencyCode())
                .containsExactlyInAnyOrder("TRY", "JPY");
    }

    @Test
    void settlesEachMerchantSeparately() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        captured(mine, 100_00, "TRY", TUESDAY);
        captured(theirs, 900_00, "TRY", TUESDAY);

        settlement.closeDay(TUESDAY);

        assertThat(settlement.forMerchant(mine, 10)).hasSize(1);
        assertThat(settlement.forMerchant(mine, 10).getFirst().get("captured"))
                .satisfies(captured -> assertThat(((Number) captured).longValue())
                        .isEqualTo(100_00L));
    }

    @Test
    void closesEveryDayThatIsDueRatherThanOnlyYesterday() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, 10_00, "TRY", TUESDAY.minusDays(3));
        captured(merchant, 20_00, "TRY", TUESDAY.minusDays(2));
        captured(merchant, 30_00, "TRY", TUESDAY.minusDays(1));

        closing.closeWhatIsDue();

        // A service that was down for three days must not leave three days unsettled forever,
        // with nobody noticing until a merchant asks where their money is.
        assertThat(batches(merchant)).isEqualTo(3);
    }

    @Test
    void leavesTodayAloneUntilItHasBeenHeardAboutInFull() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, 10_00, "TRY", LocalDate.now());

        closing.closeWhatIsDue();

        // Captures arrive asynchronously. Closing the day that is still running would strand
        // the ones that have not arrived yet in a day they did not happen in.
        assertThat(batches(merchant)).isZero();
    }

    @Test
    void showsAMerchantTheirBatchesAndWhatMadeThemUp() throws Exception {
        UUID merchant = UUID.randomUUID();
        captured(merchant, 100_00, "TRY", TUESDAY);
        captured(merchant, 200_00, "TRY", TUESDAY);
        Settlement.Closed closed = settlement.close(merchant, TUESDAY, "TRY");

        mockMvc.perform(get(settlementsOf(merchant)).with(reader(merchant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batches.length()").value(1))
                .andExpect(jsonPath("$.batches[0].captured").value(300_00))
                .andExpect(jsonPath("$.batches[0].net").value(300_00 - 930))
                .andExpect(jsonPath("$.totals[0].currency").value("TRY"));

        mockMvc.perform(get(settlementsOf(merchant) + "/" + closed.batchId() + "/items")
                        .with(reader(merchant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fee").isNumber());
    }

    @Test
    void neverAnotherMerchantsSettlements() throws Exception {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        captured(theirs, 900_00, "TRY", TUESDAY);
        Settlement.Closed closed = settlement.close(theirs, TUESDAY, "TRY");

        mockMvc.perform(get(settlementsOf(mine)).with(reader(mine)))
                .andExpect(jsonPath("$.batches.length()").value(0));

        // Naming somebody else's batch answers with nothing rather than with their payments.
        mockMvc.perform(get(settlementsOf(mine) + "/" + closed.batchId() + "/items")
                        .with(reader(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get(settlementsOf(theirs)).with(reader(mine)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOperatorCanCloseADayByHand() {
        UUID merchant = UUID.randomUUID();
        captured(merchant, 100_00, "TRY", TUESDAY);

        Map<String, Object> answer = closing.close(TUESDAY.toString());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> closed = (List<Map<String, Object>>) answer.get("closed");
        assertThat(closed).hasSize(1);
        assertThat(closed.getFirst().get("net")).isEqualTo(100_00L - (290L + 30L));

        // And asking again is not an error: the batches that exist are the answer.
        closing.close(TUESDAY.toString());
        assertThat(batches(merchant)).isEqualTo(1);
    }

    // -- helpers ---------------------------------------------------------------------------

    private UUID captured(UUID merchant, long amount, String currency, LocalDate day) {
        UUID payment = UUID.randomUUID();
        jdbc.update(
                """
                insert into settleable (payment_id, merchant_id, amount, currency,
                    captured_at, settled_for, acquirer_reference)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                payment,
                merchant,
                amount,
                currency,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                day,
                "auth_" + payment);
        return payment;
    }

    private UUID batchOf(UUID payment) {
        return jdbc.queryForObject(
                "select batch_id from settleable where payment_id = ?", UUID.class, payment);
    }

    private int batches(UUID merchant) {
        Integer counted = jdbc.queryForObject(
                "select count(*) from settlement_batch where merchant_id = ?",
                Integer.class,
                merchant);
        return counted == null ? 0 : counted;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor reader(
            UUID merchant) {
        return Callers.as(UUID.randomUUID(), merchant, Role.VIEWER);
    }

    private static String settlementsOf(UUID merchant) {
        return "/api/v1/merchants/" + merchant + "/settlements";
    }
}
