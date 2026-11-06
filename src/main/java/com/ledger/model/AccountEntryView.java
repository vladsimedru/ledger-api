package com.ledger.model;

import java.time.Instant;
import java.util.UUID;

/** One entry of an account, as listed: with its transaction's description and the balance after it. */
public record AccountEntryView(long entryId, UUID transactionId, String description, Direction direction,
                               Money amount, Money balanceAfter, Instant recordedAt) {
}
