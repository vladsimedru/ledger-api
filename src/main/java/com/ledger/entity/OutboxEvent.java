package com.ledger.entity;

import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An event waiting in the transactional outbox: one row of {@code outbox_events}. Written in the same
 * database transaction as the change it announces, then published to Kafka by the outbox relay.
 *
 * @param id          assigned by the database on insert ({@code null} before); the order events are published in
 * @param eventId     the event's own id, sent as the {@code eventId} header
 * @param messageKey  the Kafka record key
 * @param payload     the event as JSON, sent as the record value
 * @param requestId   the request that caused it, sent as the {@code requestId} header; may be {@code null}
 * @param publishedAt when Kafka acknowledged it; {@code null} until then
 */
@Table("outbox_events")
public record OutboxEvent(@Id Long id, String eventId, String eventType, String topic, String messageKey,
                          String payload, String requestId, Instant createdAt, Instant publishedAt) {

    public OutboxEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(messageKey, "messageKey");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** A new event, not yet stored or published. */
    public static OutboxEvent create(String eventId, String eventType, String topic, String messageKey,
                                     String payload, String requestId, Instant createdAt) {
        return new OutboxEvent(null, eventId, eventType, topic, messageKey, payload, requestId, createdAt, null);
    }
}
