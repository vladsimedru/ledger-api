package com.ledger.exception;

import lombok.Getter;

/** A malformed request: rejected before anything ran, never stored against an idempotency key. */
@Getter
public class BadRequestException extends RuntimeException {

    private final String code;
    private final String pointer;

    /** @param pointer JSON Pointer to the part of the request at fault, or {@code null} */
    public BadRequestException(String code, String pointer, String detail) {
        super(detail);
        this.code = code;
        this.pointer = pointer;
    }
}
