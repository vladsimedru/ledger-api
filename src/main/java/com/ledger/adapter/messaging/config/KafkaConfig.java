package com.ledger.adapter.messaging.config;

import com.ledger.adapter.messaging.exception.InvalidPaymentMessageException;
import com.ledger.domain.exception.LedgerRuleException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    private static final int PARTITIONS = 3;

    @Bean
    NewTopic paymentsSettledTopic(@Value("${ledger.topics.payments-settled}") String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic paymentsSettledDeadLetterTopic(@Value("${ledger.topics.payments-settled-dlt}") String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic transactionsTopic(@Value("${ledger.topics.transactions}") String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }

    /**
     * A message that can't be recorded must neither block the ones behind it nor vanish. A message the
     * ledger refuses, or that can't be read, goes straight to the dead-letter topic, with the reason in
     * its headers. Anything else (the database briefly unavailable, say) is retried a few times first.
     * Spring Boot wires this into the listener container.
     */
    @Bean
    CommonErrorHandler paymentsErrorHandler(KafkaOperations<Object, Object> kafkaOperations,
                                            @Value("${ledger.topics.payments-settled-dlt}") String deadLetterTopic) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, e) -> new TopicPartition(deadLetterTopic, record.partition() % PARTITIONS));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000, 3));
        handler.addNotRetryableExceptions(LedgerRuleException.class, InvalidPaymentMessageException.class);
        return handler;
    }
}
