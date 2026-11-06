package com.ledger.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An exact amount of one currency, held as a whole number of minor units (cents, yen).
 * Never a float or double: {@code 0.10 + 0.20} is exactly {@code 0.30}. Arithmetic that
 * would overflow throws instead of wrapping.
 */
public record Money(long minorUnits, LedgerCurrency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money zero(LedgerCurrency currency) {
        return new Money(0, currency);
    }

    /**
     * @throws IllegalArgumentException if the amount has more decimal places than the currency allows
     * @throws ArithmeticException      if it doesn't fit in a long number of minor units
     */
    public static Money of(BigDecimal amount, LedgerCurrency currency) {
        if (!currency.fits(amount)) {
            throw new IllegalArgumentException(amount.toPlainString() + ": " + currency.precisionRule());
        }
        return new Money(amount.movePointRight(currency.getDecimalPlaces()).longValueExact(), currency);
    }

    public static Money of(String amount, LedgerCurrency currency) {
        return of(new BigDecimal(amount), currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(minorUnits, currency.getDecimalPlaces());
    }

    /** The amount as the API writes it: plain decimal with the currency's decimal places, e.g. "125.50", "-24.50", "15000". */
    public String toPlainString() {
        return toBigDecimal().toPlainString();
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return toPlainString() + " " + currency;
    }

    private void requireSameCurrency(Money other) {
        if (other.currency != currency) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
