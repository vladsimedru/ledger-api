package com.ledger.application.port;

import com.ledger.domain.model.Transaction;
import java.util.Optional;
import java.util.UUID;

/** Append-only: there is deliberately no update or delete. */
public interface TransactionRepository {

    /**
     * Inserts the transaction and all its entries.
     *
     * @throws org.springframework.dao.DuplicateKeyException if its reversal or payment is already recorded
     */
    void insert(Transaction transaction);

    Optional<Transaction> find(UUID id);

    /** Finds a transaction and locks it until the end of the current database transaction. */
    Optional<Transaction> lock(UUID id);

    Optional<UUID> findReversalOf(UUID id);

    Optional<Transaction> findByPaymentId(String paymentId);
}
