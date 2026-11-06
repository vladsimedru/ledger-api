package com.ledger.repository;

import com.ledger.entity.Transaction;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
class TransactionRepositoryImpl implements TransactionRepository {

    private final JdbcAggregateTemplate jdbcAggregateTemplate;
    private final JdbcClient jdbcClient;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(Transaction transaction) {
        // insert(), never save(): always an INSERT of the transaction row and all its entry rows.
        try {
            jdbcAggregateTemplate.insert(transaction);
        } catch (DbActionExecutionException e) {
            // Spring Data JDBC wraps the failing statement's exception; surface a unique-key violation as such.
            if (e.getCause() instanceof DuplicateKeyException duplicate) {
                throw duplicate;
            }
            throw e;
        }
    }

    @Override
    public Optional<Transaction> find(UUID id) {
        return Optional.ofNullable(jdbcAggregateTemplate.findById(id, Transaction.class));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Transaction> lock(UUID id) {
        // A locking read takes the row lock; it is not an UPDATE, so the immutability triggers don't fire.
        boolean exists = jdbcClient.sql("SELECT id FROM transactions WHERE id = :id FOR UPDATE")
                .param("id", id.toString())
                .query(String.class)
                .optional()
                .isPresent();
        return exists ? find(id) : Optional.empty();
    }

    @Override
    public Optional<Transaction> findByPaymentId(String paymentId) {
        return jdbcClient.sql("SELECT id FROM transactions WHERE payment_id = :paymentId")
                .param("paymentId", paymentId)
                .query(String.class)
                .optional()
                .flatMap(id -> find(UUID.fromString(id)));
    }

    @Override
    public Optional<UUID> findReversalOf(UUID id) {
        return jdbcClient.sql("SELECT id FROM transactions WHERE reverses_id = :id")
                .param("id", id.toString())
                .query(String.class)
                .optional()
                .map(UUID::fromString);
    }
}
