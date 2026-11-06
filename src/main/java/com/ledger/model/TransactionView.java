package com.ledger.model;

import com.ledger.entity.Transaction;
import java.util.UUID;

/**
 * A transaction as read back.
 *
 * @param reversedBy the reversal of this transaction, or {@code null}
 */
public record TransactionView(Transaction transaction, UUID reversedBy) {
}
