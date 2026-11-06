package com.ledger.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Where money is held: one row of {@code accounts}. Amounts are whole minor units of the account's
 * currency (cents, yen), as stored. The rules that apply to an account live in
 * {@link com.ledger.domain.service.AccountService}.
 *
 * <p>Inserted once; afterwards only its balance changes, through
 * {@link com.ledger.application.port.AccountRepository#updateBalances}, under a row lock.
 *
 * @param overdraftLimit how far below zero the balance may go, in minor units; {@code null} means no
 *                       limit (only the ledger's own settlement accounts have none)
 * @param balance        in minor units
 */
@Table("accounts")
public record Account(@Id UUID id, LedgerCurrency currency, Long overdraftLimit, long balance, String label,
                      Instant createdAt) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
