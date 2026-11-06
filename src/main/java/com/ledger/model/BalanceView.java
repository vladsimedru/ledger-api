package com.ledger.model;

import com.ledger.entity.Account;
import java.time.Instant;

/** An account's balance at an instant: now, or a past {@code asOf}. */
public record BalanceView(Account account, Money balance, Instant asOf) {
}
