package com.ledger.repository;

import com.ledger.model.StoredResponse;
import java.util.Optional;

/**
 * Remembers the response given to each idempotency key. Rows are written in the same database
 * transaction as the work they describe, so a key is stored if and only if its outcome is. Keys are
 * never deleted: a retry, however late, gets the original response, and a key can never be taken
 * for a different request.
 */
public interface IdempotencyKeyRepository {

    /**
     * Claims the key for the current database transaction.
     *
     * @return empty if this transaction now owns the key (go ahead and do the work); otherwise the
     *         response stored by whoever completed it. If another transaction holds the key but hasn't
     *         finished yet, this waits for it: once it commits, its response is returned; if it rolls
     *         back, the key is claimed here instead.
     */
    Optional<StoredResponse> claim(String key, String requestHash);

    /** Records the outcome of a key claimed by the current transaction. */
    void complete(String key, StoredResponse response);
}
