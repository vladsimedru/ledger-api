package com.ledger.domain.service;

import com.ledger.domain.exception.ErrorCode;
import com.ledger.domain.exception.LedgerRuleException;
import com.ledger.domain.model.Account;
import com.ledger.domain.model.Direction;
import com.ledger.domain.model.LedgerCurrency;
import com.ledger.domain.model.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The rules for an account: opening one, applying an entry to it, and its overdraft limit. Writes nothing. */
@Service
public class AccountService {

    /**
     * An account at a zero balance.
     *
     * @param overdraftLimit {@code null} for the default limit of zero
     * @throws LedgerRuleException if the currency or the overdraft limit is refused
     */
    public Account openAccount(UUID id, String currencyCode, BigDecimal overdraftLimit, String label, Instant now) {
        LedgerCurrency currency = LedgerCurrency.of(currencyCode, "/currency");
        return new Account(id, currency, toOverdraftLimit(overdraftLimit, currency).minorUnits(), 0, label, now);
    }

    /** The account with one entry applied: a credit increases the balance, a debit decreases it. */
    public Account applyEntry(Account account, Direction direction, Money amount) {
        Money balance = getBalance(account);
        Money newBalance = direction == Direction.CREDIT ? balance.plus(amount) : balance.minus(amount);
        return new Account(account.id(), account.currency(), account.overdraftLimit(), newBalance.minorUnits(),
                account.label(), account.createdAt());
    }

    public boolean isWithinOverdraftLimit(Account account) {
        return account.overdraftLimit() == null || account.balance() >= -account.overdraftLimit();
    }

    public Money getBalance(Account account) {
        return new Money(account.balance(), account.currency());
    }

    /** @return {@code null} for an account without a limit */
    public Money getOverdraftLimit(Account account) {
        return account.overdraftLimit() == null ? null : new Money(account.overdraftLimit(), account.currency());
    }

    /** How the account is named in a refusal: its label and id, or just its id. */
    public String getDisplayName(Account account) {
        String label = account.label();
        return label != null && !label.isBlank() ? label + " (" + account.id() + ")" : account.id().toString();
    }

    private static Money toOverdraftLimit(BigDecimal overdraftLimit, LedgerCurrency currency) {
        if (overdraftLimit == null) {
            return Money.zero(currency);
        }
        if (overdraftLimit.signum() < 0) {
            throw new LedgerRuleException("/overdraftLimit", ErrorCode.AMOUNT_NOT_POSITIVE,
                    "The overdraft limit can't be negative");
        }
        if (!currency.fits(overdraftLimit)) {
            throw new LedgerRuleException("/overdraftLimit", ErrorCode.AMOUNT_PRECISION_EXCEEDED,
                    currency.precisionRule());
        }
        try {
            return Money.of(overdraftLimit, currency);
        } catch (ArithmeticException e) {
            throw new LedgerRuleException("/overdraftLimit", ErrorCode.AMOUNT_OUT_OF_RANGE,
                    "The overdraft limit is too large");
        }
    }
}
