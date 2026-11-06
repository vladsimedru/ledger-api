package com.ledger.service;

import com.ledger.model.StoredResponse;
import org.springframework.http.ResponseEntity;

/**
 * What {@link IdempotencyService} found or did for a key, inside its database transaction.
 *
 * @param response the stored response: the one replayed, or the one just stored
 * @param fresh    the response the work just returned; {@code null} for a retry or a refusal
 * @param replayed whether the response was already stored by an earlier attempt
 */
record IdempotencyOutcome<T>(StoredResponse response, ResponseEntity<T> fresh, boolean replayed) {
}
