package dev.kauzes.mizan.common.money;

import java.util.Currency;

/** Thrown when an operation combines two amounts in different currencies. */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(Currency left, Currency right) {
        super("cannot combine " + left.getCurrencyCode() + " with " + right.getCurrencyCode());
    }
}
