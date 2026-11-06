package com.ledger.model;

import com.ledger.entity.Account;
import com.ledger.entity.Transaction;
import java.util.List;

/**
 * What {@link com.ledger.service.PostingService#post} decided: the transaction to record and
 * the new state of the accounts it touches. Nothing is written yet; the caller persists both.
 *
 * @param transaction     the transaction to record
 * @param updatedAccounts every account it touches, with its new balance
 */
public record Posting(Transaction transaction, List<Account> updatedAccounts) {
}
