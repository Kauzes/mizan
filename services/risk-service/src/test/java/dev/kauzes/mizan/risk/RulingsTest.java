package dev.kauzes.mizan.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The one place on this platform where what somebody did changes what it decides next.
 *
 * <p>So the tests worth writing are not really about learning. They are about the bounds: that
 * it takes a pattern rather than an afternoon, that it stops moving well before the scorer
 * stops working, and that a ruling cannot be rewritten afterwards. A loop nobody bounded is a
 * loop an attacker teaches, one released payment at a time.
 */
@SpringBootTest
class RulingsTest extends MizanIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Rulings rulings;

    @Autowired
    private Thresholds thresholds;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aMerchantWhoseAnalystsKeepReleasingStopsSeeingSoMuch() throws Exception {
        UUID merchant = UUID.randomUUID();
        int reviewAbove = thresholds.forMerchant(merchant)[0];

        rule(merchant, "RELEASED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholdMovedBy").value(0));
        rule(merchant, "RELEASED").andExpect(jsonPath("$.thresholdMovedBy").value(0));

        // Three the same way is a pattern. Two is an afternoon.
        rule(merchant, "RELEASED")
                .andExpect(jsonPath("$.thresholdMovedBy").value(5))
                .andExpect(jsonPath("$.learnedAdjustment").value(5));

        assertThat(thresholds.forMerchant(merchant)[0])
                .as("the line moved up, so fewer of this merchant's payments are held")
                .isEqualTo(reviewAbove + 5);

        // And then it takes another three, rather than moving on every ruling from here.
        // A run of six is a stronger signal than a run of three and should be worth more; it
        // should be worth twice as much rather than four times, or the loop reaches its limit
        // in six rulings and is not the slow thing it is supposed to be.
        rule(merchant, "RELEASED").andExpect(jsonPath("$.thresholdMovedBy").value(0));
        rule(merchant, "RELEASED").andExpect(jsonPath("$.thresholdMovedBy").value(0));
        rule(merchant, "RELEASED").andExpect(jsonPath("$.thresholdMovedBy").value(5));

        assertThat(rulings.adjustmentFor(merchant)).isEqualTo(10);
    }

    @Test
    void andOneWhoseAnalystsKeepRefusingSeesMore() throws Exception {
        UUID merchant = UUID.randomUUID();
        int blockAbove = thresholds.forMerchant(merchant)[1];

        for (int i = 0; i < 3; i++) {
            rule(merchant, "REFUSED").andExpect(status().isOk());
        }

        assertThat(rulings.adjustmentFor(merchant)).isEqualTo(-5);
        assertThat(thresholds.forMerchant(merchant)[1]).isEqualTo(blockAbove - 5);
    }

    @Test
    void analystsWhoDisagreeWithEachOtherTeachNothing() throws Exception {
        UUID merchant = UUID.randomUUID();

        rule(merchant, "RELEASED");
        rule(merchant, "RELEASED");
        rule(merchant, "REFUSED");
        rule(merchant, "RELEASED");
        rule(merchant, "RELEASED");

        // Five rulings, four of them releases, and nothing has moved. What is being learned
        // from is a run of agreement: a count that never reset would eventually move on
        // noise, and noise is what a merchant's analysts disagreeing looks like.
        assertThat(rulings.adjustmentFor(merchant)).isZero();
    }

    @Test
    void theLoopStopsWellBeforeTheScorerDoes() throws Exception {
        UUID merchant = UUID.randomUUID();

        // Thirty consecutive releases: somebody with an unlimited supply of held payments,
        // teaching the platform to stop looking at them.
        for (int i = 0; i < 30; i++) {
            rule(merchant, "RELEASED").andExpect(status().isOk());
        }

        assertThat(rulings.adjustmentFor(merchant))
                .as("twenty points and not one more, whatever they do")
                .isEqualTo(20);
        assertThat(thresholds.forMerchant(merchant)[1])
                .as("and the block line is still a line")
                .isEqualTo(90);
    }

    @Test
    void andTheDatabaseWouldRefuseItEvenIfTheLoopDidNot() {
        UUID merchant = UUID.randomUUID();
        thresholds.set(merchant, 40, 70, "a test");

        // A bound the loop enforces is a bound the loop can be wrong about, so it is written
        // down twice. This is the half that does not depend on this code being right.
        assertThatThrownBy(() -> jdbc.update(
                        "update merchant_thresholds set learned_adjustment = 40 "
                                + "where merchant_id = ?",
                        merchant))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void whatAPersonAskedForSurvivesWhatTheLoopInferred() throws Exception {
        UUID merchant = UUID.randomUUID();
        thresholds.set(merchant, 30, 60, "the merchant");

        for (int i = 0; i < 3; i++) {
            rule(merchant, "RELEASED");
        }

        assertThat(thresholds.forMerchant(merchant)).containsExactly(35, 65);

        // And what they asked for is still there underneath, so somebody can see how far the
        // platform has drifted from their instruction and put it back.
        assertThat(jdbc.queryForObject(
                        "select review_above from merchant_thresholds where merchant_id = ?",
                        Integer.class,
                        merchant))
                .isEqualTo(30);
    }

    @Test
    void aPaymentIsRuledOnOnce() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID payment = UUID.randomUUID();

        rule(merchant, payment, "RELEASED").andExpect(status().isOk());
        rule(merchant, payment, "REFUSED").andExpect(status().isConflict());
    }

    @Test
    void aRulingCannotBeRewritten() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        rule(merchant, payment, "RELEASED").andExpect(status().isOk());

        // A ruling is evidence of a decision somebody made, and evidence the next decision
        // can overwrite is not evidence. The same reasoning as the journal.
        assertThatThrownBy(() -> jdbc.update(
                        "update ruling set ruling = 'REFUSED' where payment_id = ?", payment))
                .hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> jdbc.update("delete from ruling where payment_id = ?", payment))
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void somethingOtherThanReleasedOrRefusedIsNotARuling() throws Exception {
        mockMvc.perform(post("/api/v1/risk/rulings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"merchantId":"%s","paymentId":"%s","ruling":"MAYBE",
                                 "ruledBy":"grace","why":"not sure"}
                                """
                                        .formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void whatWasRuledIsReadableAfterwards() throws Exception {
        UUID merchant = UUID.randomUUID();
        rule(merchant, "RELEASED");

        mockMvc.perform(get("/api/v1/risk/rulings/merchants/" + merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulings[0].ruling").value("RELEASED"))
                .andExpect(jsonPath("$.rulings[0].ruled_by").value("grace"))
                .andExpect(jsonPath("$.rulings[0].risk_score").value(55))
                .andExpect(jsonPath("$.learnedAdjustment").value(0));
    }

    private ResultActions rule(UUID merchant, String ruling) throws Exception {
        return rule(merchant, UUID.randomUUID(), ruling);
    }

    private ResultActions rule(UUID merchant, UUID payment, String ruling) throws Exception {
        return mockMvc.perform(post("/api/v1/risk/rulings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        {"merchantId":"%s","paymentId":"%s","ruling":"%s","riskScore":55,
                         "riskReasons":"the amount is unusual for this merchant",
                         "ruledBy":"grace","why":"known customer, they called to confirm"}
                        """
                                .formatted(merchant, payment, ruling)));
    }
}
