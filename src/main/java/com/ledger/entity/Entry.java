package com.ledger.entity;

import com.ledger.model.Direction;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A recorded posting to one account: one row of {@code entries}, part of its {@link Transaction}.
 * Amounts are whole minor units of the transaction's currency. The row's auto-increment {@code id}
 * isn't mapped: it only orders an account's entries for the read queries.
 *
 * @param amount       always positive
 * @param balanceAfter the account's balance right after this entry was applied
 */
@Table("entries")
public record Entry(UUID accountId, Direction direction, long amount, long balanceAfter, Instant recordedAt) {

    public Entry {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
