package com.ledger.repository;

import com.ledger.model.StoredResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
class IdempotencyKeyRepositoryImpl implements IdempotencyKeyRepository {

    private final JdbcClient jdbcClient;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StoredResponse> claim(String key, String requestHash) {
        try {
            // The INSERT locks the key's index entry until commit. A concurrent INSERT of the same key
            // blocks on that lock: when we commit it fails as a duplicate, when we roll back it succeeds.
            jdbcClient.sql("""
                            INSERT INTO idempotency_keys (idem_key, request_hash, created_at)
                            VALUES (:key, :hash, CURRENT_TIMESTAMP(6))""")
                    .param("key", key)
                    .param("hash", requestHash)
                    .update();
            return Optional.empty();
        } catch (DuplicateKeyException e) {
            // Completed by a committed transaction: under READ COMMITTED we now see its row. Keys are
            // never deleted, so it is there.
            return Optional.of(jdbcClient.sql("""
                            SELECT request_hash, status_code, response_body, location
                            FROM idempotency_keys WHERE idem_key = :key""")
                    .param("key", key)
                    .query((rs, n) -> new StoredResponse(rs.getString("request_hash"), rs.getInt("status_code"),
                            rs.getString("response_body"), rs.getString("location")))
                    .single());
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(String key, StoredResponse response) {
        jdbcClient.sql("""
                        UPDATE idempotency_keys SET status_code = :status, response_body = :body, location = :location
                        WHERE idem_key = :key""")
                .param("status", response.status())
                .param("body", response.body())
                .param("location", response.location())
                .param("key", key)
                .update();
    }
}
