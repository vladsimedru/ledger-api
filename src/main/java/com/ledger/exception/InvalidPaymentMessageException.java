package com.ledger.exception;

/** A payment-settled message that can't be read: retrying it would never help. */
public class InvalidPaymentMessageException extends RuntimeException {

    public InvalidPaymentMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
