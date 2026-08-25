package dev.kauzes.mizan.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency TRY = Currency.getInstance("TRY");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Test
    void addsAndSubtractsWithinOneCurrency() {
        Money a = Money.of(12550L, TRY);
        Money b = Money.of(450L, TRY);

        assertThat(a.plus(b)).isEqualTo(Money.of(13000L, TRY));
        assertThat(a.minus(b)).isEqualTo(Money.of(12100L, TRY));
    }

    @Test
    void rejectsArithmeticAcrossCurrencies() {
        Money lira = Money.of(100L, TRY);
        Money dollars = Money.of(100L, USD);

        assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(() -> lira.plus(dollars))
                .withMessageContaining("TRY")
                .withMessageContaining("USD");
    }

    @Test
    void rejectsComparisonAcrossCurrencies() {
        assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(() -> Money.of(1L, TRY).compareTo(Money.of(1L, USD)));
    }

    @Test
    void failsLoudlyOnOverflow() {
        Money huge = Money.of(Long.MAX_VALUE, TRY);

        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> huge.plus(Money.of(1L, TRY)));
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> huge.times(2L));
    }

    @Test
    void rejectsTheUnnegatableAmount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of(Long.MIN_VALUE, TRY));
    }

    @Test
    void negatesAndReportsSign() {
        Money debit = Money.of(-2500L, TRY);

        assertThat(debit.isNegative()).isTrue();
        assertThat(debit.negate()).isEqualTo(Money.of(2500L, TRY));
        assertThat(debit.abs()).isEqualTo(Money.of(2500L, TRY));
        assertThat(Money.zero(TRY).isZero()).isTrue();
    }

    @Test
    void splitsWithoutLosingAMinorUnit() {
        List<Money> parts = Money.of(1000L, TRY).split(3);

        assertThat(parts).containsExactly(
                Money.of(334L, TRY), Money.of(333L, TRY), Money.of(333L, TRY));
        assertThat(sum(parts)).isEqualTo(Money.of(1000L, TRY));
    }

    @Test
    void allocatesByWeightAndKeepsTheTotal() {
        List<Money> parts = Money.of(10000L, TRY).allocate(70L, 30L);

        assertThat(parts).containsExactly(Money.of(7000L, TRY), Money.of(3000L, TRY));
        assertThat(sum(parts)).isEqualTo(Money.of(10000L, TRY));
    }

    @Test
    void allocatesAwkwardWeightsWithoutDrift() {
        List<Money> parts = Money.of(9999L, TRY).allocate(1L, 1L, 1L, 1L, 1L, 1L, 1L);

        assertThat(sum(parts)).isEqualTo(Money.of(9999L, TRY));
        assertThat(parts).hasSize(7);
    }

    @Test
    void allocatesNegativeAmountsWithoutDrift() {
        List<Money> parts = Money.of(-1000L, TRY).split(3);

        assertThat(sum(parts)).isEqualTo(Money.of(-1000L, TRY));
        assertThat(parts).allSatisfy(part -> assertThat(part.isNegative()).isTrue());
    }

    @Test
    void allocationIsExhaustiveAcrossManyAmounts() {
        for (long amount = -500L; amount <= 500L; amount++) {
            for (int parts = 1; parts <= 7; parts++) {
                assertThat(sum(Money.of(amount, TRY).split(parts)))
                        .as("amount %d into %d parts", amount, parts)
                        .isEqualTo(Money.of(amount, TRY));
            }
        }
    }

    @Test
    void rejectsMeaninglessAllocations() {
        Money amount = Money.of(100L, TRY);

        assertThatIllegalArgumentException().isThrownBy(amount::allocate);
        assertThatIllegalArgumentException().isThrownBy(() -> amount.allocate(0L, 0L));
        assertThatIllegalArgumentException().isThrownBy(() -> amount.allocate(1L, -1L));
        assertThatIllegalArgumentException().isThrownBy(() -> amount.split(0));
    }

    @Test
    void formatsUsingTheCurrencyScale() {
        assertThat(Money.of(12550L, TRY)).hasToString("125.50 TRY");
        assertThat(Money.of(-5L, TRY)).hasToString("-0.05 TRY");
        assertThat(Money.of(1500L, JPY)).hasToString("1500 JPY");
    }

    @Test
    void buildsFromAnIsoCode() {
        assertThat(Money.of(1L, "TRY")).isEqualTo(Money.of(1L, TRY));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Money.of(1L, "XYZQ"));
    }

    private static Money sum(List<Money> parts) {
        return parts.stream().reduce(Money.zero(TRY), Money::plus);
    }
}
