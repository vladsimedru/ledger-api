package com.ledger.messaging;

import java.time.Instant;
import java.util.List;

/**
 * The transaction inside a {@link TransactionRecordedEvent}. Part of the Kafka contract, kept separate from
 * the API's DTOs so that one can't change the other. Ids and amounts are strings, as in the API.
 */
public record TransactionPayload(String id, String description, String currency, Instant recordedAt,
                                 String reversesTransactionId, String paymentId, List<EntryPayload> entries) {
}
