package com.ledger.application.port;

import com.ledger.domain.model.Transaction;

/**
 * Publishes the event that a transaction was recorded, for other services. Must be called inside the
 * database transaction that records it: the event is then stored if and only if the transaction is.
 */
public interface TransactionEventPublisher {

    void publishTransactionRecorded(Transaction transaction);
}
