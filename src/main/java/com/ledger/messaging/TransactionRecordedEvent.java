package com.ledger.messaging;

import java.time.Instant;
import java.util.List;

/**
 * The announcement of a recorded transaction, as published on {@code ledger.transactions}.
 * Additive changes only within v1.
 */
public record TransactionRecordedEvent(String eventId, String eventType, String requestId, Body transaction) {

    public record Body(String id, String description, String currency, Instant recordedAt,
                       String reversesTransactionId, String paymentId, List<Line> entries) {
    }

    public record Line(String accountId, String direction, String amount, String balanceAfter) {
    }
}
