package dev.kauzes.mizan.banksim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * The acquirer, and the four things it can be made to do.
 *
 * <p>Everything interesting about a payment platform happens when the bank does something
 * other than approve, so the value of this service is entirely in whether those cases can be
 * provoked on demand. A decline that cannot be provoked is a path nobody has run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "mizan.acquirer.slow-response=1s")
class AcquirerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String GOOD_CARD = "4000000000000000";
    private static final String NO_FUNDS = "4000000000000002";
    private static final String DO_NOT_HONOUR = "4000000000000005";
    private static final String STOLEN = "4000000000000007";
    private static final String SLOW = "4000000000000069";
    private static final String SLOW_DECLINE = "4000000000000068";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void approvesAnOrdinaryCard() throws Exception {
        authorize(request(), GOOD_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.state").value("HELD"))
                .andExpect(jsonPath("$.acquirerReference").isNotEmpty())
                .andExpect(jsonPath("$.cardLastFour").value("0000"));
    }

    @Test
    void declinesWithAReasonTheMerchantCanBeTold() throws Exception {
        authorize(request(), NO_FUNDS)
                .andExpect(jsonPath("$.outcome").value("DECLINED"))
                .andExpect(jsonPath("$.reason").value("insufficient_funds"))
                .andExpect(jsonPath("$.state").value("REFUSED"));

        authorize(request(), DO_NOT_HONOUR)
                .andExpect(jsonPath("$.reason").value("do_not_honour"));

        authorize(request(), STOLEN).andExpect(jsonPath("$.reason").value("stolen_card"));
    }

    @Test
    void anythingUnrecognisedIsAnOrdinaryApproval() throws Exception {
        authorize(request(), "4111111111111111")
                .andExpect(jsonPath("$.outcome").value("APPROVED"));
    }

    @Test
    void keepsOnlyTheLastFourDigits() throws Exception {
        String body = bodyOf(authorize(request(), GOOD_CARD));

        assertThat(body)
                .as("a simulator has no more business keeping a card number than a bank does")
                .doesNotContain(GOOD_CARD)
                .contains("0000");
    }

    @Test
    void theSameRequestTwiceAuthorizesOnce() throws Exception {
        String requestId = request();

        String first = bodyOf(authorize(requestId, GOOD_CARD));
        String second = bodyOf(authorize(requestId, GOOD_CARD));

        assertThat(JSON.readTree(second))
                .as("a caller who did not hear the answer will ask again, and must not be "
                        + "charged twice for it")
                .isEqualTo(JSON.readTree(first));
    }

    @Test
    void takesAnAuthorizedAmount() throws Exception {
        String reference = referenceFrom(authorize(request(), GOOD_CARD));

        mockMvc.perform(post("/acquirer/authorizations/" + reference + "/capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CAPTURED"));
    }

    @Test
    void releasesAnAuthorizedAmount() throws Exception {
        String reference = referenceFrom(authorize(request(), GOOD_CARD));

        mockMvc.perform(post("/acquirer/authorizations/" + reference + "/void"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("VOIDED"));
    }

    @Test
    void takingSomethingTwiceTakesItOnce() throws Exception {
        String reference = referenceFrom(authorize(request(), GOOD_CARD));
        mockMvc.perform(post("/acquirer/authorizations/" + reference + "/capture"))
                .andExpect(status().isOk());

        // A caller whose answer was lost has to be able to ask again. Saying "already taken"
        // is far better than taking it twice.
        mockMvc.perform(post("/acquirer/authorizations/" + reference + "/capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CAPTURED"));
    }

    @Test
    void givesBackMoneyItTook_inPartAndThenTheRest() throws Exception {
        String reference = referenceFrom(authorize(request(), GOOD_CARD));
        mockMvc.perform(post("/acquirer/authorizations/" + reference + "/capture"))
                .andExpect(status().isOk());

        refund(reference, "r1", 25000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(25000))
                .andExpect(jsonPath("$.refundedInTotal").value(25000))
                .andExpect(jsonPath("$.remaining").value(100000));

        refund(reference, "r2", 100000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundedInTotal").value(125000))
                .andExpect(jsonPath("$.remaining").value(0));
    }

    @Test
    void willNotGiveBackMoreThanItTook() throws Exception {
        String reference = referenceFrom(authorize(request(), GOOD_CARD));
        mockMvc.perform(post("/acquirer/authorizations/" + reference + "/capture"))
                .andExpect(status().isOk());
        refund(reference, "r1", 100000).andExpect(status().isOk());

        // Its own arithmetic, not something it trusts a caller to have got right.
        refund(reference, "r2", 25001)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("cannot refund 25001 of 125000 when 100000 has already been "
                                + "refunded"));
    }

    @Test
    void givingBackTwiceWithOneReferenceGivesBackOnce() throws Exception {
        String reference = referenceFrom(authorize(request(), GOOD_CARD));
        mockMvc.perform(post("/acquirer/authorizations/" + reference + "/capture"))
                .andExpect(status().isOk());

        refund(reference, "same-reference", 25000).andExpect(status().isOk());
        refund(reference, "same-reference", 25000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundedInTotal").value(25000));
    }

    @Test
    void willNotGiveBackMoneyItNeverTook() throws Exception {
        String held = referenceFrom(authorize(request(), GOOD_CARD));

        // Authorized and not captured. Releasing a reservation is a void, and calling it a
        // refund would mean the books recording a movement that never happened.
        refund(held, "r1", 1000)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("an authorization that is HELD cannot be refunded; only money "
                                + "that was taken can be given back"));
    }

    private ResultActions refund(String reference, String ourReference, long amount)
            throws Exception {

        return mockMvc.perform(post("/acquirer/authorizations/" + reference + "/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + ourReference + "\",\"amount\":" + amount + "}"));
    }

    @Test
    void refusesToContradictItself() throws Exception {
        String voided = referenceFrom(authorize(request(), GOOD_CARD));
        mockMvc.perform(post("/acquirer/authorizations/" + voided + "/void"))
                .andExpect(status().isOk());

        // Repeating is one thing; reversing is another.
        mockMvc.perform(post("/acquirer/authorizations/" + voided + "/capture"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value("an authorization that is VOIDED cannot be captured"));

        String refused = referenceFrom(authorize(request(), NO_FUNDS));
        mockMvc.perform(post("/acquirer/authorizations/" + refused + "/capture"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void saysWhatHappenedToARequestWhenAsked() throws Exception {
        String requestId = request();
        String reference = referenceFrom(authorize(requestId, GOOD_CARD));

        mockMvc.perform(get("/acquirer/authorizations").param("requestId", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acquirerReference").value(reference))
                .andExpect(jsonPath("$.outcome").value("APPROVED"));
    }

    @Test
    void hasNoRecordOfARequestItNeverSaw() throws Exception {
        mockMvc.perform(get("/acquirer/authorizations").param("requestId", request()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @Timeout(30)
    void aSlowCardReservesTheMoneyBeforeItStopsAnswering() throws Exception {
        String requestId = request();

        // The call that will not come back in time.
        CompletableFuture<Void> inFlight = CompletableFuture.runAsync(() -> {
            try {
                authorize(requestId, SLOW);
            } catch (Exception ignored) {
                // The caller of a slow authorization is expected to give up.
            }
        });

        // Meanwhile, the answer is already there for anybody who asks. This is the state
        // MIZ-44 has to resolve: money reserved, caller none the wiser.
        assertThat(lookedUpWithin(requestId, Duration.ofSeconds(10)))
                .as("the authorization exists before the caller is told about it")
                .isTrue();

        inFlight.join();
    }

    /** Polls the lookup, because the answer appears before the slow call returns. */
    private boolean lookedUpWithin(String requestId, Duration patience) throws Exception {
        long giveUpAt = System.nanoTime() + patience.toNanos();

        while (System.nanoTime() < giveUpAt) {
            int status = mockMvc
                    .perform(get("/acquirer/authorizations").param("requestId", requestId))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            if (status == 200) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    @Timeout(30)
    void aSlowDeclineIsRecordedAsADeclineRatherThanAnApproval() throws Exception {
        String requestId = request();

        CompletableFuture<Void> inFlight = CompletableFuture.runAsync(() -> {
            try {
                authorize(requestId, SLOW_DECLINE);
            } catch (Exception ignored) {
                // Expected: the caller gives up.
            }
        });

        // A resolver that assumed a timeout meant approval would be wrong exactly here.
        assertThat(lookedUpWithin(requestId, Duration.ofSeconds(10))).isTrue();
        mockMvc.perform(get("/acquirer/authorizations").param("requestId", requestId))
                .andExpect(jsonPath("$.outcome").value("DECLINED"))
                .andExpect(jsonPath("$.reason").value("do_not_honour"));

        inFlight.join();
    }

    private String request() {
        return "payment-" + UUID.randomUUID();
    }

    private ResultActions authorize(String requestId, String card) {
        try {
            return mockMvc.perform(post("/acquirer/authorizations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"requestId":"%s","amount":125000,"currency":"TRY","card":"%s"}
                            """.formatted(requestId, card)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String referenceFrom(ResultActions actions) throws Exception {
        return JSON.readTree(bodyOf(actions)).path("acquirerReference").asString();
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
