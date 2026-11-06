package com.ledger.messaging;

import com.ledger.entity.OutboxEvent;
import com.ledger.repository.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the transactional outbox to Kafka. One instance at a time relays (the others find the
 * relay lock taken and skip their turn), in outbox id order. An event is marked published only after
 * Kafka has acknowledged it ({@code acks=all}); if the relay crashes in between, it is sent
 * again: at-least-once, never lost.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    @Value("${ledger.outbox.batch-size}")
    private final int batchSize;

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

    /** @return how many events were published; 0 if there were none or another instance is relaying */
    int publishBatch() {
        Integer published = transactionTemplate.execute(tx -> {
            if (!outboxEventRepository.tryLockRelay()) {
                return 0;
            }
            List<OutboxEvent> events = outboxEventRepository.findUnpublished(batchSize);
            if (events.isEmpty()) {
                return 0;
            }
            sendAndAwaitAcknowledgement(events);
            outboxEventRepository.markPublished(events.stream().map(OutboxEvent::id).toList());
            return events.size();
        });
        return published == null ? 0 : published;
    }

    /** Sent in order; the idempotent producer keeps that order per partition, even on retries. */
    private void sendAndAwaitAcknowledgement(List<OutboxEvent> events) {
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>();
        for (OutboxEvent event : events) {
            sends.add(kafkaTemplate.send(toProducerRecord(event)));
        }
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka did not acknowledge the batch", e);
        }
    }

    private static ProducerRecord<String, String> toProducerRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.messageKey(), event.payload());
        record.headers().add("eventId", event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", event.eventType().getBytes(StandardCharsets.UTF_8));
        if (event.requestId() != null) {
            record.headers().add("requestId", event.requestId().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
