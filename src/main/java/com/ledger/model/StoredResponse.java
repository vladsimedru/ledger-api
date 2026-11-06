package com.ledger.model;

/**
 * The response stored for an idempotency key, replayed to every retry.
 *
 * @param location the created resource's path, or {@code null}
 */
public record StoredResponse(String requestHash, int status, String body, String location) {
}
