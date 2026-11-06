package com.ledger.exception;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Error codes that belong to the HTTP layer; the ledger's own are {@link com.ledger.exception.ErrorCode}. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiErrorCode {

    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String IDEMPOTENCY_KEY_MISSING = "IDEMPOTENCY_KEY_MISSING";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
}
