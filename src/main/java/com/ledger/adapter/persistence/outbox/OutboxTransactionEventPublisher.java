package com.ledger.adapter.persistence.outbox;

import com.ledger.application.port.TransactionEventPublisher;
import com.ledger.application.support.UuidV7;
import com.ledger.domain.model.Transaction;
import java.sql.Timestamp;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox: the event is a row written in the same database transaction as the
 * ledger change, so it exists if and only if the transaction was recorded. A relay publishes the
 * rows to Kafka afterwards, in id order.
 */
@Component
@RequiredArgsConstructor
class OutboxTransactionEventPublisher implements TransactionEventPublisher {

    static final String EVENT_TYPE = "ledger.transaction.recorded.v1";

    private final JdbcClient jdbcClient;
    private final JsonMapper jsonMapper;
    private final TransactionEventMapper transactionEventMapper;
    @Value("${ledger.topics.transactions}")
    private final String topic;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishTransactionRecorded(Transaction transaction) {
        String eventId = UuidV7.next().toString();
        String requestId = MDC.get("requestId");
        TransactionRecordedEvent event = new TransactionRecordedEvent(eventId, EVENT_TYPE, requestId,
                transactionEventMapper.toEventBody(transaction));
        jdbcClient.sql("""
                        INSERT INTO outbox_events
                            (event_id, event_type, topic, message_key, payload, request_id, created_at)
                        VALUES (:eventId, :type, :topic, :key, :payload, :requestId, :createdAt)""")
                .param("eventId", eventId)
                .param("type", EVENT_TYPE)
                .param("topic", topic)
                // Keyed by currency: a transaction's accounts all share it, so every transaction touching
                // a given account goes to the same partition, in the order the outbox relay sends them.
                .param("key", transaction.currency().name())
                .param("payload", jsonMapper.writeValueAsString(event))
                .param("requestId", requestId)
                .param("createdAt", Timestamp.from(transaction.recordedAt()))
                .update();
    }
}
