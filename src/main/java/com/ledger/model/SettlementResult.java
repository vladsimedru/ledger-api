package com.ledger.model;

import com.ledger.entity.Transaction;

/**
 * The outcome of recording a settled payment.
 *
 * @param duplicate whether the payment had already been recorded (nothing new was written)
 */
public record SettlementResult(Transaction transaction, boolean duplicate) {
}
