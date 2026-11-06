package com.ledger.domain.model;

/** A credit increases an account's balance; a debit decreases it. */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction flip() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
