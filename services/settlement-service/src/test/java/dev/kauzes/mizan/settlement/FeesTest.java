package dev.kauzes.mizan.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The fee arithmetic, which is the whole reason this service has any arithmetic at all.
 *
 * <p>One property matters more than every particular case: the fee charged on a batch and the
 * fees attributed to the payments in it are the same number. A merchant who adds up their own
 * statement and gets a different figure from the one they were charged has found a discrepancy,
 * and a discrepancy of one kurus destroys trust out of all proportion to one kurus.
 */
class FeesTest {

    private static final Currency TRY = Currency.getInstance("TRY");
    private static final Currency JPY = Currency.getInstance("JPY");

    /** 2.9% and 30 minor units a payment, which is roughly what a real one costs. */
    private final Fees fees = new Fees(290, 30);

    @Test
    void chargesAShareOfTheTotalAndAFixedPartPerPayment() {
        Fees.Charged charged = fees.charge(List.of(100_00L, 200_00L), TRY);

        assertThat(charged.captured().amount()).isEqualTo(300_00L);
        // 2.9% of 300.00 is 8.70, and two payments at 0.30 is 0.60.
        assertThat(charged.fee().amount()).isEqualTo(870L + 60L);
        assertThat(charged.net().amount()).isEqualTo(300_00L - 930L);
    }

    @Test
    void theFeeOnTheBatchIsTheSumOfTheFeesOnItsPayments() {
        // Three payments that do not divide evenly: 2.9% of each, rounded, would not sum to
        // 2.9% of the total. Allocating from the total is what makes them agree.
        Fees.Charged charged = fees.charge(List.of(333L, 333L, 334L), TRY);

        assertThat(charged.perPayment()).hasSize(3);
        assertThat(charged.perPayment().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(charged.fee().amount());
    }

    @Test
    void andThatHoldsForAnyBatchAnybodyCanThinkOf() {
        // A property rather than an example. The record's own constructor asserts it too, so
        // this would fail loudly rather than subtly, which is the point of putting it there.
        Random unlucky = new Random(20260912);

        for (int batch = 0; batch < 500; batch++) {
            List<Long> amounts = unlucky
                    .longs(1 + unlucky.nextInt(40), 1L, 5_000_00L)
                    .boxed()
                    .toList();

            Fees.Charged charged = fees.charge(amounts, TRY);

            assertThat(charged.perPayment().stream().mapToLong(Long::longValue).sum())
                    .as("the fees attributed to %s payments must come to the batch fee", amounts.size())
                    .isEqualTo(charged.fee().amount());
            assertThat(charged.captured().minus(charged.fee())).isEqualTo(charged.net());
            assertThat(charged.fee().amount()).isLessThan(charged.captured().amount());
        }
    }

    @Test
    void attributesMoreOfTheFeeToTheLargerPayment() {
        Fees.Charged charged = fees.charge(List.of(100L, 10_000L), TRY);

        // The percentage part follows the amount; the fixed part does not. A merchant asking
        // why a small payment cost proportionally more is asking about the fixed part, and
        // this arrangement is what makes that answerable.
        assertThat(charged.perPayment().get(0)).isLessThan(charged.perPayment().get(1));
        assertThat(charged.perPayment().get(0)).isGreaterThanOrEqualTo(30L);
    }

    @Test
    void roundsHalfUpAndSaysSo() {
        // 2.9% of 0.50 is 0.0145, which is a hundredth and a half of a kurus. Half up takes
        // it; rounding down would mean the platform gives away a fraction of every batch.
        assertThat(new Fees(290, 0).charge(List.of(50L), TRY).fee().amount()).isEqualTo(1L);
        // And 2.9% of 0.17 is 0.00493, which is not half of anything.
        assertThat(new Fees(290, 0).charge(List.of(17L), TRY).fee().amount()).isZero();
    }

    @Test
    void worksInACurrencyWithNoMinorUnits() {
        // The arithmetic is in minor units throughout, so a currency with none is not a
        // special case — which is exactly why nothing here divides by a hundred.
        Fees.Charged charged = new Fees(290, 0).charge(List.of(10_000L), JPY);

        assertThat(charged.fee().amount()).isEqualTo(290L);
        assertThat(charged.fee().currency()).isEqualTo(JPY);
    }

    @Test
    void chargesNothingWhenThereIsNoFee() {
        Fees.Charged free = new Fees(0, 0).charge(List.of(100_00L), TRY);

        assertThat(free.fee().isZero()).isTrue();
        assertThat(free.net()).isEqualTo(free.captured());
    }

    @Test
    void refusesABatchWithNoPaymentsAndAFeeThatIsNotOne() {
        assertThatThrownBy(() -> fees.charge(List.of(), TRY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fees(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fees(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
