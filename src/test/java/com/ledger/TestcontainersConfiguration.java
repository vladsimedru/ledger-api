package com.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * The real infrastructure the integration tests run against: MySQL and Kafka, one of each for the
 * whole test run, shared by every Spring test context (and by the second instance in MultiInstanceIT).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("ledger")
            // Same as docker-compose: lets the non-SUPER user create the immutability triggers.
            .withCommand("--log-bin-trust-function-creators=1");

    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        return MYSQL;
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return KAFKA;
    }
}
