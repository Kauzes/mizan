package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The map, tested before any journey uses it. Most of these transitions are performed by
 * stories after this one; the rules they will find are these.
 */
class PaymentStatusTest {

    @Test
    void aPaymentStartsByBeingAuthorizedRefusedOrLeftInDoubt() {
        // The third one is not a failure mode of the payment but of the answer: the acquirer
        // was asked and did not say. MIZ-44 resolves it by asking again rather than guessing.
        assertThat(PaymentStatus.CREATED.next())
                .containsExactlyInAnyOrder(
                        PaymentStatus.AUTHORIZED,
                        PaymentStatus.DECLINED,
                        PaymentStatus.AUTHORIZATION_UNKNOWN);
    }

    @Test
    void notKnowingIsSomewhereAPaymentCanLeave() {
        assertThat(PaymentStatus.AUTHORIZATION_UNKNOWN.isFinal())
                .as("a payment nobody knows the outcome of is not finished, it is unresolved")
                .isFalse();
        assertThat(PaymentStatus.AUTHORIZATION_UNKNOWN.next())
                .containsExactlyInAnyOrder(PaymentStatus.AUTHORIZED, PaymentStatus.DECLINED);
    }

    @Test
    void aDecidedPaymentCannotBecomeUndecided() {
        for (PaymentStatus decided : EnumSet.of(
                PaymentStatus.AUTHORIZED,
                PaymentStatus.DECLINED,
                PaymentStatus.CAPTURED,
                PaymentStatus.VOIDED)) {

            assertThat(decided.canMoveTo(PaymentStatus.AUTHORIZATION_UNKNOWN))
                    .as("%s is an answer, and an answer does not become a question", decided)
                    .isFalse();
        }
    }

    @Test
    void anAuthorizationIsEitherTakenOrReleased() {
        assertThat(PaymentStatus.AUTHORIZED.next())
                .containsExactlyInAnyOrder(PaymentStatus.CAPTURED, PaymentStatus.VOIDED);
    }

    @Test
    void theEndsAreEnds() {
        for (PaymentStatus end :
                EnumSet.of(PaymentStatus.DECLINED, PaymentStatus.CAPTURED, PaymentStatus.VOIDED)) {

            assertThat(end.isFinal()).as("%s should be final", end).isTrue();
            assertThat(end.next()).as("%s should go nowhere", end).isEmpty();
        }
    }

    @Test
    void nothingGoesBackToTheBeginning() {
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(status.canMoveTo(PaymentStatus.CREATED))
                    .as("%s should not be able to become CREATED again", status)
                    .isFalse();
        }
    }

    @Test
    void moneyThatMovedIsNotUndoneByAStateChange() {
        // A captured payment is corrected by a refund, which is Epic 7, and not by quietly
        // becoming voided.
        assertThat(PaymentStatus.CAPTURED.canMoveTo(PaymentStatus.VOIDED)).isFalse();
        assertThat(PaymentStatus.VOIDED.canMoveTo(PaymentStatus.CAPTURED)).isFalse();
    }

    @Test
    void aDeclinedPaymentIsNotCapturable() {
        assertThat(PaymentStatus.DECLINED.canMoveTo(PaymentStatus.CAPTURED)).isFalse();
        assertThat(PaymentStatus.DECLINED.canMoveTo(PaymentStatus.AUTHORIZED)).isFalse();
    }

    @Test
    void everyStateAgreesWithItself() {
        for (PaymentStatus status : PaymentStatus.values()) {
            Set<PaymentStatus> next = status.next();
            assertThat(status.isFinal()).isEqualTo(next.isEmpty());
            next.forEach(allowed -> assertThat(status.canMoveTo(allowed)).isTrue());

            EnumSet.complementOf(EnumSet.copyOf(
                            next.isEmpty() ? EnumSet.noneOf(PaymentStatus.class)
                                    : EnumSet.copyOf(next)))
                    .forEach(refused -> assertThat(status.canMoveTo(refused))
                            .as("%s should refuse %s", status, refused)
                            .isFalse());
        }
    }
}
