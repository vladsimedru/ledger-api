package com.ledger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads a topic from the beginning, as an outside consumer would. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TestTopics {

    /** Reads {@code topic} until {@code done} holds for the matching records read so far, or the timeout. */
    public static List<ConsumerRecord<String, String>> readUntil(String topic, Predicate<ConsumerRecord<String, String>> matching,
                                                                 Predicate<List<ConsumerRecord<String, String>>> done,
                                                                 Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            while (!done.test(found) && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(250)).forEach(r -> {
                    if (matching.test(r)) {
                        found.add(r);
                    }
                });
            }
        }
        return found;
    }

    public static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
