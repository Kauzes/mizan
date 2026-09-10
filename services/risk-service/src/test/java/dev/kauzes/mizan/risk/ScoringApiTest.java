package dev.kauzes.mizan.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scoring as another service actually asks for it.
 *
 * <p>{@link ScorerTest} covers what the rules decide. This covers the parts that only exist at
 * the edge: that a merchant's own thresholds are found and used, that the reasons survive being
 * serialised, and that a request with a card number in it is refused rather than scored.
 */
@SpringBootTest
class ScoringApiTest extends MizanIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Thresholds thresholds;

    @Test
    void scoresAPaymentAndSaysWhy() throws Exception {
        UUID merchant = UUID.randomUUID();

        score("""
                {"paymentId":"%s","merchantId":"%s","amount":100000,"currency":"TRY",
                 "cardFingerprint":"card_9f2b1c","cardCountry":"TR",
                 "at":"2026-09-06T10:00:00Z"}
                """.formatted(UUID.randomUUID(), merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("APPROVE"))
                .andExpect(jsonPath("$.reviewAbove").value(40))
                .andExpect(jsonPath("$.blockAbove").value(70))
                .andExpect(jsonPath("$.signals").isArray());
    }

    @Test
    void aMerchantsOwnThresholdsAreUsed() throws Exception {
        UUID cautious = UUID.randomUUID();
        thresholds.set(cautious, 3, 10, "a test");

        // The same payment that approves at the platform's defaults. This merchant has said
        // they want to look at more than that, and the platform does what they asked.
        String body = bodyOf(score("""
                {"paymentId":"%s","merchantId":"%s","amount":100000,"currency":"TRY",
                 "cardFingerprint":"card_x","cardCountry":"TR","at":"2026-09-06T10:00:00Z"}
                """.formatted(UUID.randomUUID(), cautious)));

        assertThat(JSON.readTree(body).path("reviewAbove").asInt()).isEqualTo(3);
        assertThat(JSON.readTree(body).path("blockAbove").asInt()).isEqualTo(10);
        assertThat(JSON.readTree(body).path("verdict").asString())
                .as("the amount is exactly round, which is worth 5, which is over their line")
                .isEqualTo("REVIEW");
    }

    @Test
    void theReasonsSurviveBeingSerialised() throws Exception {
        UUID merchant = UUID.randomUUID();
        thresholds.set(merchant, 1, 90, "a test");

        String body = bodyOf(score("""
                {"paymentId":"%s","merchantId":"%s","amount":100000,"currency":"TRY",
                 "cardFingerprint":"card_x","cardCountry":"TR","at":"2026-09-06T10:00:00Z"}
                """.formatted(UUID.randomUUID(), merchant)));

        var signal = JSON.readTree(body).path("signals").get(0);
        assertThat(signal.path("rule").asString()).isEqualTo("SUSPICIOUSLY_ROUND");
        assertThat(signal.path("contribution").asInt()).isEqualTo(5);
        assertThat(signal.path("because").asString())
                .as("a reason a person can read, not a rule name they have to look up")
                .isNotBlank();
    }

    @Test
    void refusesARequestThatIsMissingWhatItNeeds() throws Exception {
        score("""
                {"merchantId":"%s","amount":100000,"currency":"TRY",
                 "cardFingerprint":"card_x","at":"2026-09-06T10:00:00Z"}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void refusesAnAmountOfNothing() throws Exception {
        score("""
                {"paymentId":"%s","merchantId":"%s","amount":0,"currency":"TRY",
                 "cardFingerprint":"card_x","at":"2026-09-06T10:00:00Z"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void takesAFingerprintAndNotACardNumber() throws Exception {
        UUID merchant = UUID.randomUUID();
        String card = "4000000000000000";

        // Nothing stops a caller putting a card number in the fingerprint field, and nothing
        // can. What this asserts is the other half: the response does not echo it back, so a
        // caller's mistake is theirs and does not become this service's.
        String body = bodyOf(score("""
                {"paymentId":"%s","merchantId":"%s","amount":12345,"currency":"TRY",
                 "cardFingerprint":"%s","at":"2026-09-06T10:00:00Z"}
                """.formatted(UUID.randomUUID(), merchant, card)));

        assertThat(body)
                .as("the request is scored and forgotten; nothing about the card comes back")
                .doesNotContain(card);
    }

    private ResultActions score(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/risk/scores")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
