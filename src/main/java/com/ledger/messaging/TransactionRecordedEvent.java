package com.ledger.messaging;

/**
 * The announcement of a recorded transaction, as published on {@code ledger.transactions}.
 * Additive changes only within v1.
 */
public record TransactionRecordedEvent(String eventId, String eventType, String requestId,
                                       TransactionPayload transaction) {
}
