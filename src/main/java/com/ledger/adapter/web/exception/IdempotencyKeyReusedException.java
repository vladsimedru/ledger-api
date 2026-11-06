package com.ledger.adapter.web.exception;

/** The idempotency key was already used for a different request. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' was already used for a different request;"
                + " use a new key for a new request");
    }
}
