package dev.kauzes.mizan.common.money;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * A monetary amount as a signed count of minor units in one ISO 4217 currency.
 * 12550 TRY means 125.50. Arithmetic across currencies is rejected, and every
 * operation fails loudly on overflow rather than wrapping.
 */
public record Money(long amount, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amount == Long.MIN_VALUE) {
            throw new IllegalArgumentException("amount is out of range");
        }
    }

    public static Money of(long amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(long amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amount, other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amount, other.amount), currency);
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(amount, factor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amount), currency);
    }

    public Money abs() {
        return amount < 0 ? negate() : this;
    }

    public boolean isZero() {
        return amount == 0L;
    }

    public boolean isPositive() {
        return amount > 0L;
    }

    public boolean isNegative() {
        return amount < 0L;
    }

    /**
     * Splits the amount across the given weights so that the parts always add back
     * to the whole. Leftover minor units go to the largest remainders first, which
     * keeps a three way split of 10.00 at 3.34 / 3.33 / 3.33 rather than losing a kurus.
     */
    public List<Money> allocate(long... weights) {
        if (weights.length == 0) {
            throw new IllegalArgumentException("at least one weight is required");
        }
        long totalWeight = 0L;
        for (long weight : weights) {
            if (weight < 0L) {
                throw new IllegalArgumentException("weights must not be negative");
            }
            totalWeight = Math.addExact(totalWeight, weight);
        }
        if (totalWeight == 0L) {
            throw new IllegalArgumentException("weights must not sum to zero");
        }
        long sign = amount < 0L ? -1L : 1L;
        long magnitude = Math.abs(amount);
        long[] shares = new long[weights.length];
        long[] remainders = new long[weights.length];
        long distributed = 0L;

        for (int i = 0; i < weights.length; i++) {
            long numerator = Math.multiplyExact(magnitude, weights[i]);
            shares[i] = numerator / totalWeight;
            remainders[i] = numerator % totalWeight;
            distributed = Math.addExact(distributed, shares[i]);
        }

        long leftover = magnitude - distributed;
        for (long unit = 0L; unit < leftover; unit++) {
            int best = -1;
            for (int i = 0; i < remainders.length; i++) {
                if (remainders[i] > 0L && (best < 0 || remainders[i] > remainders[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            shares[best]++;
            remainders[best] = 0L;
        }

        List<Money> parts = new ArrayList<>(shares.length);
        for (long share : shares) {
            parts.add(new Money(sign * share, currency));
        }
        return List.copyOf(parts);
    }

    public List<Money> split(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be at least one");
        }
        long[] weights = new long[parts];
        java.util.Arrays.fill(weights, 1L);
        return allocate(weights);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amount, other.amount);
    }

    @Override
    public String toString() {
        int digits = Math.max(0, currency.getDefaultFractionDigits());
        if (digits == 0) {
            return amount + " " + currency.getCurrencyCode();
        }
        long unit = 1L;
        for (int i = 0; i < digits; i++) {
            unit *= 10L;
        }
        long magnitude = Math.abs(amount);
        String sign = amount < 0L ? "-" : "";
        return sign + (magnitude / unit) + "." + String.format("%0" + digits + "d", magnitude % unit)
                + " " + currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
