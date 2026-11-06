package com.ledger.exception;

/**
 * One reason a request was refused.
 *
 * @param pointer JSON Pointer to the part of the request at fault (e.g. {@code /entries/1/amount}),
 *                or {@code null} when the request as a whole is at fault
 */
public record Violation(String pointer, ErrorCode code, String detail) {
}
