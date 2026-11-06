package com.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ledger.adapter.web.filter.RequestIdFilter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two instances of the service, sharing one MySQL and one Kafka, with requests spread across both:
 * the guarantees must hold however requests are interleaved between instances.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MultiInstanceIT {

    private static final String SETTLEMENT_EUR = "00000000-0000-0000-0000-000000000978";

    static ConfigurableApplicationContext second;

    @Autowired
    WebApplicationContext webApplicationContext;
    @Autowired
    JdbcClient jdbcClient;
    @Autowired
    JsonMapper jsonMapper;

    List<MockMvc> instances;

    @BeforeAll
    static void startSecondInstance() {
        // The same variables docker-compose sets: application.yml reads them.
        second = new SpringApplicationBuilder(LedgerApplication.class).properties(
                "server.port=0",
                "LEDGER_DB_URL=" + TestcontainersConfiguration.MYSQL.getJdbcUrl(),
                "LEDGER_DB_USER=" + TestcontainersConfiguration.MYSQL.getUsername(),
                "LEDGER_DB_PASSWORD=" + TestcontainersConfiguration.MYSQL.getPassword(),
                "LEDGER_KAFKA_BOOTSTRAP=" + TestcontainersConfiguration.KAFKA.getBootstrapServers()).run();
    }

    @AfterAll
    static void stopSecondInstance() {
        if (second != null) {
            second.close();
        }
    }

    private List<MockMvc> instances() {
        if (instances == null) {
            instances = List.of(mvc(webApplicationContext), mvc((WebApplicationContext) second));
        }
        return instances;
    }

    private static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(RequestIdFilter.class)).build();
    }

    @Test
    void retries_landing_on_either_instance_take_effect_once_with_the_same_answer() throws Exception {
        String merchant = openAccount(0);
        String key = UUID.randomUUID().toString();
        String body = transfer(SETTLEMENT_EUR, merchant, "5.00");

        List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int instance = i % 2;
            attempts.add(() -> post(instance, "/v1/transactions", key, body));
        }
        List<MockHttpServletResponse> responses = concurrently(attempts);

        Set<String> bodies = new HashSet<>();
        for (MockHttpServletResponse r : responses) {
            assertThat(r.getStatus()).isEqualTo(201);
            bodies.add(r.getContentAsString());
        }
        assertThat(bodies).hasSize(1);
        assertThat(balance(merchant)).isEqualTo("5.00");
    }

    @Test
    void money_moving_both_ways_through_both_instances_balances_exactly_and_is_announced_in_order() throws Exception {
        String a = openAccount(0);
        String b = openAccount(1);
        post(0, "/v1/transactions", UUID.randomUUID().toString(), transfer(SETTLEMENT_EUR, a, "100.00"));
        post(1, "/v1/transactions", UUID.randomUUID().toString(), transfer(SETTLEMENT_EUR, b, "100.00"));

        List<Callable<MockHttpServletResponse>> work = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int instance = i % 2;
            work.add(() -> post(instance, "/v1/transactions", UUID.randomUUID().toString(), transfer(a, b, "1.00")));
            work.add(() -> post(1 - instance, "/v1/transactions", UUID.randomUUID().toString(), transfer(b, a, "3.00")));
        }
        assertThat(concurrently(work)).allSatisfy(r -> assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201));

        assertThat(balance(a)).isEqualTo("180.00"); // 100 - 40 + 120
        assertThat(balance(b)).isEqualTo("20.00");  // 100 + 40 - 120

        // Announcements touching account a arrive in the order its entries were applied.
        List<String> applied = jdbcClient.sql("SELECT transaction_id FROM entries WHERE account_id = ? ORDER BY id")
                .param(a).query(String.class).list();
        List<ConsumerRecord<String, String>> announced = TestTopics.readUntil("ledger.transactions",
                r -> r.value().contains(a),
                found -> found.stream().map(this::transactionId).distinct().count() == applied.size(),
                Duration.ofSeconds(60));
        assertThat(announced.stream().map(this::transactionId).distinct().toList()).containsExactlyElementsOf(applied);
    }

    // --- helpers ---

    private MockHttpServletResponse post(int instance, String path, String key, String body) throws Exception {
        return instances().get(instance).perform(MockMvcRequestBuilders.post(path)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private String openAccount(int instance) throws Exception {
        MockHttpServletResponse r = post(instance, "/v1/accounts", UUID.randomUUID().toString(), "{\"currency\":\"EUR\"}");
        assertThat(r.getStatus()).isEqualTo(201);
        return jsonMapper.readTree(r.getContentAsString()).get("id").asString();
    }

    private String balance(String account) throws Exception {
        return jsonMapper.readTree(instances().get(1).perform(get("/v1/accounts/" + account)).andReturn().getResponse()
                .getContentAsString()).get("balance").asString();
    }

    private String transactionId(ConsumerRecord<String, String> r) {
        return jsonMapper.readTree(r.value()).at("/transaction/id").asString();
    }

    private static String transfer(String from, String to, String amount) {
        return """
                {"description":"test","entries":[
                  {"accountId":"%s","direction":"DEBIT","amount":"%s"},
                  {"accountId":"%s","direction":"CREDIT","amount":"%s"}]}""".formatted(from, amount, to, amount);
    }

    private static <T> List<T> concurrently(List<Callable<T>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : work) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }
}
