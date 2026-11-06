package com.ledger.adapter.messaging.outbox;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the transactional outbox to Kafka. One instance at a time relays (the others find the
 * relay lock taken and skip their turn), in outbox id order. A row is marked published only after
 * Kafka has acknowledged it ({@code acks=all}); if the relay crashes in between, the row is sent
 * again: at-least-once, never lost.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class OutboxRelay {

    private static final int MYSQL_LOCK_NOWAIT = 3572;

    private final JdbcClient jdbcClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    @Value("${ledger.outbox.batch-size}")
    private final int batchSize;

    private record OutboxRow(long id, String eventId, String eventType, String topic, String key, String payload,
                             String requestId) {
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval}")
    void relay() {
        try {
            while (publishBatch() == batchSize) {
                // a full batch: there may be more waiting
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay failed; unpublished events will be retried: {}", e.toString());
        }
    }

    int publishBatch() {
        Integer published = transactionTemplate.execute(tx -> {
            if (!tryTakeRelayLock()) {
                return 0;
            }
            List<OutboxRow> rows = jdbcClient.sql("""
                            SELECT id, event_id, event_type, topic, message_key, payload, request_id
                            FROM outbox_events WHERE published_at IS NULL ORDER BY id LIMIT :limit""")
                    .param("limit", batchSize)
                    .query((rs, n) -> new OutboxRow(rs.getLong("id"), rs.getString("event_id"),
                            rs.getString("event_type"), rs.getString("topic"), rs.getString("message_key"),
                            rs.getString("payload"), rs.getString("request_id")))
                    .list();
            if (rows.isEmpty()) {
                return 0;
            }
            // Sent in order; the idempotent producer keeps that order per partition, even on retries.
            List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>();
            for (OutboxRow row : rows) {
                sends.add(kafkaTemplate.send(toRecord(row)));
            }
            try {
                CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("Kafka did not acknowledge the batch", e);
            }
            jdbcClient.sql("UPDATE outbox_events SET published_at = CURRENT_TIMESTAMP(6) WHERE id IN (:ids)")
                    .param("ids", rows.stream().map(OutboxRow::id).toList())
                    .update();
            return rows.size();
        });
        return published == null ? 0 : published;
    }

    private boolean tryTakeRelayLock() {
        try {
            jdbcClient.sql("SELECT id FROM outbox_relay_lock WHERE id = 1 FOR UPDATE NOWAIT").query(Integer.class).single();
            return true;
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql && sql.getErrorCode() == MYSQL_LOCK_NOWAIT) {
                return false; // another instance is relaying
            }
            throw e;
        }
    }

    private static ProducerRecord<String, String> toRecord(OutboxRow row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
        record.headers().add("eventId", row.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        if (row.requestId() != null) {
            record.headers().add("requestId", row.requestId().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
