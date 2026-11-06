package com.ledger.messaging;

import com.ledger.entity.OutboxEvent;
import com.ledger.entity.Transaction;
import com.ledger.filter.RequestIdFilter;
import com.ledger.mapper.TransactionEventMapper;
import com.ledger.repository.OutboxEventRepository;
import com.ledger.support.UuidV7;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox: the event is a row written in the same database transaction as the
 * ledger change, so it exists if and only if the transaction was recorded. {@link OutboxRelay} publishes
 * the rows to Kafka afterwards, in order.
 */
@Component
@RequiredArgsConstructor
class OutboxTransactionEventPublisher implements TransactionEventPublisher {

    static final String EVENT_TYPE = "ledger.transaction.recorded.v1";

    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;
    private final TransactionEventMapper transactionEventMapper;
    @Value("${ledger.topics.transactions}")
    private final String topic;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishTransactionRecorded(Transaction transaction) {
        String eventId = UuidV7.next().toString();
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        TransactionRecordedEvent event = new TransactionRecordedEvent(eventId, EVENT_TYPE, requestId,
                transactionEventMapper.toTransactionPayload(transaction));
        // Keyed by currency: a transaction's accounts all share it, so every transaction touching a given
        // account goes to the same partition, in the order the outbox relay sends them.
        String messageKey = transaction.currency().name();
        outboxEventRepository.insert(OutboxEvent.create(eventId, EVENT_TYPE, topic, messageKey,
                jsonMapper.writeValueAsString(event), requestId, transaction.recordedAt()));
    }
}
