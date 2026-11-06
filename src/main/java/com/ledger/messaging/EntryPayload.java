package com.ledger.messaging;

/** One entry of a {@link TransactionPayload}, with the account's balance after it. */
public record EntryPayload(String accountId, String direction, String amount, String balanceAfter) {
}
