package com.ledger.service;

import static com.ledger.model.LedgerCurrency.EUR;
import static com.ledger.model.LedgerCurrency.JPY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledger.entity.Account;
import com.ledger.exception.ErrorCode;
import com.ledger.exception.LedgerRuleException;
import com.ledger.model.Direction;
import com.ledger.model.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final UUID ID = UUID.fromString("01960000-0000-7000-8000-000000000042");

    private final AccountService accountService = new AccountService();

    @Test
    void a_new_account_starts_at_zero_with_no_overdraft_by_default() {
        Account account = accountService.openAccount(ID, "EUR", null, "Merchant 42", NOW);

        assertThat(account.id()).isEqualTo(ID);
        assertThat(account.currency()).isEqualTo(EUR);
        assertThat(account.balance()).isZero();
        assertThat(account.overdraftLimit()).isZero();
        assertThat(account.label()).isEqualTo("Merchant 42");
        assertThat(account.createdAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @CsvSource({
            "EUR, 100.50, 10050",
            "EUR, 0,      0",
            "JPY, 5000,   5000"
    })
    void the_overdraft_limit_is_kept_in_minor_units(String currency, BigDecimal limit, long minorUnits) {
        assertThat(accountService.openAccount(ID, currency, limit, null, NOW).overdraftLimit()).isEqualTo(minorUnits);
    }

    @ParameterizedTest
    @CsvSource({
            "EUR, -0.01,  AMOUNT_NOT_POSITIVE",
            "EUR, 0.001,  AMOUNT_PRECISION_EXCEEDED",
            "JPY, 1.5,    AMOUNT_PRECISION_EXCEEDED",
            "EUR, 1E+30,  AMOUNT_OUT_OF_RANGE"
    })
    void a_bad_overdraft_limit_is_refused(String currency, BigDecimal limit, ErrorCode code) {
        assertRefused(() -> accountService.openAccount(ID, currency, limit, null, NOW), "/overdraftLimit", code);
    }

    @ParameterizedTest
    @CsvSource({
            "eur, INVALID_CURRENCY",
            "QQQ, INVALID_CURRENCY",
            "GBP, UNSUPPORTED_CURRENCY"
    })
    void a_currency_the_ledger_doesnt_hold_is_refused(String currency, ErrorCode code) {
        assertRefused(() -> accountService.openAccount(ID, currency, null, null, NOW), "/currency", code);
    }

    @Test
    void a_credit_increases_the_balance_and_a_debit_decreases_it() {
        Account account = new Account(ID, EUR, 0L, 10000, "Merchant 42", NOW);

        Account credited = accountService.applyEntry(account, Direction.CREDIT, Money.of("25.50", EUR));
        Account debited = accountService.applyEntry(account, Direction.DEBIT, Money.of("25.50", EUR));

        assertThat(credited.balance()).isEqualTo(12550);
        assertThat(debited.balance()).isEqualTo(7450);
        assertThat(debited).usingRecursiveComparison().ignoringFields("balance").isEqualTo(account);
    }

    @Test
    void an_account_may_go_below_zero_down_to_its_overdraft_limit() {
        assertThat(accountService.isWithinOverdraftLimit(new Account(ID, EUR, 10000L, -10000, null, NOW))).isTrue();
        assertThat(accountService.isWithinOverdraftLimit(new Account(ID, EUR, 10000L, -10001, null, NOW))).isFalse();
        assertThat(accountService.isWithinOverdraftLimit(new Account(ID, EUR, 0L, -1, null, NOW))).isFalse();
    }

    @Test
    void an_account_without_a_limit_can_go_as_low_as_needed() {
        Account settlement = new Account(ID, EUR, null, Long.MIN_VALUE + 1, "Settlement EUR", NOW);

        assertThat(accountService.isWithinOverdraftLimit(settlement)).isTrue();
        assertThat(accountService.getOverdraftLimit(settlement)).isNull();
    }

    @Test
    void the_balance_and_limit_are_money_in_the_accounts_currency() {
        Account account = new Account(ID, JPY, 5000L, -1200, null, NOW);

        assertThat(accountService.getBalance(account)).isEqualTo(new Money(-1200, JPY));
        assertThat(accountService.getOverdraftLimit(account)).isEqualTo(new Money(5000, JPY));
    }

    @Test
    void an_account_is_named_by_its_label_and_id_or_just_its_id() {
        assertThat(accountService.getDisplayName(new Account(ID, EUR, 0L, 0, "Merchant 42", NOW)))
                .isEqualTo("Merchant 42 (" + ID + ")");
        assertThat(accountService.getDisplayName(new Account(ID, EUR, 0L, 0, " ", NOW))).isEqualTo(ID.toString());
        assertThat(accountService.getDisplayName(new Account(ID, EUR, 0L, 0, null, NOW))).isEqualTo(ID.toString());
    }

    private static void assertRefused(Runnable action, String pointer, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(LedgerRuleException.class, e -> {
                    assertThat(e.code()).isEqualTo(code);
                    assertThat(e.getViolations().get(0).pointer()).isEqualTo(pointer);
                });
    }
}
