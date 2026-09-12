package dev.kauzes.mizan.settlement;

import dev.kauzes.mizan.common.money.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What this platform charges for taking a payment, and how that is divided up.
 *
 * <p>The whole of this class exists because of one requirement that sounds obvious and is not:
 * the fee charged on a batch and the fees attributed to the payments in it must be the same
 * number, to the minor unit. Anything else and a merchant adding up their own statement gets a
 * different answer from the one they were charged, which is the kind of discrepancy that
 * destroys trust in a platform far out of proportion to the amount involved.
 *
 * <p>So the percentage is worked out once, on the batch total, and then <em>allocated</em>
 * across the payments by amount. The parts add back to the whole by construction rather than
 * by luck. Charging 2.9% of each payment and summing would not: three payments of 3.33 each
 * lose a kurus at every rounding, and nothing in the system would notice.
 */
@Component
public class Fees {

    /** Out of ten thousand, because a percentage of a percentage needs the precision. */
    private final int basisPoints;

    private final long fixedPerPayment;

    public Fees(
            @Value("${mizan.settlement.fee-basis-points:290}") int basisPoints,
            @Value("${mizan.settlement.fee-fixed-per-payment:30}") long fixedPerPayment) {

        if (basisPoints < 0 || fixedPerPayment < 0) {
            throw new IllegalArgumentException("a fee cannot be negative");
        }
        this.basisPoints = basisPoints;
        this.fixedPerPayment = fixedPerPayment;
    }

    public int basisPoints() {
        return basisPoints;
    }

    public long fixedPerPayment() {
        return fixedPerPayment;
    }

    /** What a batch of these payments comes to, and what each of them contributed. */
    public Charged charge(List<Long> amounts, Currency currency) {
        if (amounts.isEmpty()) {
            throw new IllegalArgumentException("there is no batch without payments");
        }

        Money captured = Money.of(amounts.stream().mapToLong(Long::longValue).sum(), currency);

        // Once, on the total. Rounded half up, which is the ordinary commercial rounding and
        // the only one worth arguing about out loud: rounding down would mean the platform
        // quietly gives away a fraction of every batch, and rounding up means taking one.
        long share = roundedHalfUp(captured.amount(), basisPoints, 10_000);
        Money percentage = Money.of(share, currency);
        Money fixed = Money.of(Math.multiplyExact(fixedPerPayment, amounts.size()), currency);
        Money fee = percentage.plus(fixed);

        return new Charged(captured, fee, captured.minus(fee), perPayment(amounts, percentage));
    }

    /**
     * What each payment contributed to the fee.
     *
     * <p>The percentage part is allocated by amount, so the parts sum to exactly the figure
     * charged. The fixed part is the same for every payment and needs no allocating, which is
     * the point of it being fixed.
     */
    private List<Long> perPayment(List<Long> amounts, Money percentage) {
        long[] weights = amounts.stream().mapToLong(Long::longValue).toArray();
        List<Money> shares = percentage.allocate(weights);

        List<Long> attributed = new ArrayList<>(amounts.size());
        for (Money share : shares) {
            attributed.add(Math.addExact(share.amount(), fixedPerPayment));
        }
        return List.copyOf(attributed);
    }

    /**
     * What a batch came to, and what each payment in it contributed.
     *
     * @param perPayment in the same order as the amounts given, and summing to exactly
     *     {@code fee}
     */
    public record Charged(Money captured, Money fee, Money net, List<Long> perPayment) {

        public Charged {
            long attributed = perPayment.stream().mapToLong(Long::longValue).sum();
            if (attributed != fee.amount()) {
                // Not a defensive check somebody can delete. This is the invariant the class
                // exists for, and if it ever fails the arithmetic above is wrong rather than
                // the caller.
                throw new IllegalStateException(
                        "the fees attributed to payments come to "
                                + attributed
                                + " but the batch was charged "
                                + fee.amount());
            }
        }
    }

    /** Half up, in integers, because a double has no business anywhere near a fee. */
    private static long roundedHalfUp(long amount, long numerator, long denominator) {
        long scaled = Math.multiplyExact(amount, numerator);
        long doubled = Math.addExact(Math.multiplyExact(scaled, 2L), denominator);
        return doubled / Math.multiplyExact(denominator, 2L);
    }
}
