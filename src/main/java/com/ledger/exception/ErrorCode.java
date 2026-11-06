package com.ledger.exception;

/** Why the ledger refused something. Stable: these names are part of the API contract. */
public enum ErrorCode {
    INVALID_CURRENCY,
    UNSUPPORTED_CURRENCY,
    AMOUNT_NOT_POSITIVE,
    AMOUNT_PRECISION_EXCEEDED,
    AMOUNT_OUT_OF_RANGE,
    TOO_FEW_ENTRIES,
    /** Every entry posts to the same account: no money would move. */
    SAME_ACCOUNT,
    UNKNOWN_ACCOUNT,
    CURRENCY_MISMATCH,
    UNBALANCED,
    OVERDRAFT_LIMIT_EXCEEDED,
    ALREADY_REVERSED,
    REVERSAL_NOT_REVERSIBLE,
    /** A payment-settled message for a payment already recorded with different details. */
    PAYMENT_CONFLICT
}
