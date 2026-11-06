package com.ledger.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** An entry as requested, before the ledger has checked it against its account. */
public record EntryDraft(UUID accountId, Direction direction, BigDecimal amount) {

    public EntryDraft {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
    }
}
