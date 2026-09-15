package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kauzes.mizan.banksim.AuthorizationState;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The acquirer's words for where an authorization is, as this service reads them.
 *
 * <p>The capture sweep (MIZ-90) decides what to do with money by the state the acquirer reports:
 * record a capture, clear a capture that never arrived, or ask a person. A word this service
 * expects and the acquirer never sends is not an error anybody sees. The sweep simply finds nothing
 * it recognises and hands every payment to a person. That is what the first version did, expecting
 * AUTHORIZED where the acquirer says HELD, and only a test that happened to exercise that path
 * noticed. So the words are checked against the simulator's own list, and a rename on either side
 * fails here.
 */
class AcquirerStatesTest {

    @Test
    void everyStateTheCaptureSweepActsOnIsOneTheAcquirerSends() {
        assertThat(Arrays.stream(AuthorizationState.values()).map(Enum::name))
                .contains(
                        AcquirerClient.AcquirerDecision.CAPTURED,
                        AcquirerClient.AcquirerDecision.HELD);
    }

    @Test
    void heldIsTheOnlyStateThatMeansTheCaptureNeverArrived() {
        AcquirerClient.AcquirerDecision held = decisionIn(AcquirerClient.AcquirerDecision.HELD);
        assertThat(held.stillOnlyAuthorized()).isTrue();
        assertThat(held.captured()).isFalse();

        // Everything else the acquirer can say is either a capture or something a person decides.
        for (AuthorizationState state : AuthorizationState.values()) {
            AcquirerClient.AcquirerDecision decision = decisionIn(state.name());
            assertThat(decision.stillOnlyAuthorized())
                    .as("%s should not be read as a capture that never arrived", state)
                    .isEqualTo(state.name().equals(AcquirerClient.AcquirerDecision.HELD));
        }
    }

    private static AcquirerClient.AcquirerDecision decisionIn(String state) {
        return new AcquirerClient.AcquirerDecision(
                "auth_test", true, null, "0000", java.time.Instant.now(), state);
    }
}
