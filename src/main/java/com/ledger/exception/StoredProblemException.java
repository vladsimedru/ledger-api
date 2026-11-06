package com.ledger.exception;

import lombok.Getter;

/** A problem response that has been stored against an idempotency key, to be sent exactly as stored. */
@Getter
public class StoredProblemException extends RuntimeException {

    private final int status;
    private final String body;
    private final boolean replayed;

    /** @param replayed whether this is a retry being given the stored response */
    public StoredProblemException(int status, String body, boolean replayed) {
        super("HTTP " + status, null, false, false);
        this.status = status;
        this.body = body;
        this.replayed = replayed;
    }
}
