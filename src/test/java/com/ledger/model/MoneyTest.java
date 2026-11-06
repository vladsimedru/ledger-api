package com.ledger.model;

import static com.ledger.model.LedgerCurrency.EUR;
import static com.ledger.model.LedgerCurrency.JPY;
import static com.ledger.model.LedgerCurrency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    @ParameterizedTest
    @CsvSource({
            "125.50, EUR, 12550",
            "0.01,   EUR, 1",
            "1.500,  EUR, 150",
            "15000,  JPY, 15000",
            "-24.50, USD, -2450"
    })
    void an_amount_is_held_in_whole_minor_units(String amount, LedgerCurrency currency, long minorUnits) {
        assertThat(Money.of(amount, currency).minorUnits()).isEqualTo(minorUnits);
    }

    @Test
    void more_decimal_places_than_the_currency_has_are_refused() {
        assertThatThrownBy(() -> Money.of("0.001", EUR)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1.5", JPY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void an_amount_too_large_for_minor_units_is_refused_not_wrapped() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("1E+30"), EUR)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void arithmetic_is_exact() {
        assertThat(Money.of("0.10", EUR).plus(Money.of("0.20", EUR))).isEqualTo(Money.of("0.30", EUR));
        assertThat(Money.of("100.00", EUR).minus(Money.of("124.50", EUR))).isEqualTo(Money.of("-24.50", EUR));
        assertThat(Money.of("24.50", EUR).negate()).isEqualTo(Money.of("-24.50", EUR));
    }

    @Test
    void arithmetic_that_would_overflow_throws() {
        Money max = new Money(Long.MAX_VALUE, EUR);

        assertThatThrownBy(() -> max.plus(new Money(1, EUR))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new Money(Long.MIN_VALUE, EUR).negate()).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void different_currencies_dont_mix() {
        Money eur = Money.of("1.00", EUR);
        Money usd = Money.of("1.00", USD);

        assertThatThrownBy(() -> eur.plus(usd)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eur.minus(usd)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eur.compareTo(usd)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "12550,  EUR, 125.50",
            "-2450,  EUR, -24.50",
            "0,      EUR, 0.00",
            "15000,  JPY, 15000"
    })
    void it_is_written_with_the_currencys_decimal_places(long minorUnits, LedgerCurrency currency, String expected) {
        assertThat(new Money(minorUnits, currency).toPlainString()).isEqualTo(expected);
    }

    @Test
    void it_reads_as_amount_and_currency() {
        assertThat(Money.of("125.50", EUR)).hasToString("125.50 EUR");
    }

    @Test
    void only_amounts_above_zero_are_positive() {
        assertThat(Money.of("0.01", EUR).isPositive()).isTrue();
        assertThat(Money.zero(EUR).isPositive()).isFalse();
        assertThat(Money.of("-0.01", EUR).isPositive()).isFalse();
    }

    @Test
    void amounts_compare_by_value() {
        assertThat(Money.of("1.00", EUR)).isLessThan(Money.of("1.01", EUR));
        assertThat(Money.of("-5.00", EUR)).isLessThan(Money.zero(EUR));
    }
}
