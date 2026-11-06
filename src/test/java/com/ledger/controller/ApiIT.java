package com.ledger.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ledger.TestcontainersConfiguration;
import com.ledger.filter.RequestIdFilter;
import com.ledger.messaging.TransactionEventPublisher;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The REST API end to end against a real MySQL: retries, refusals, errors. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApiIT {

    private static final String SETTLEMENT_EUR = "00000000-0000-0000-0000-000000000978";

    @Autowired
    WebApplicationContext webApplicationContext;
    @Autowired
    RequestIdFilter requestIdFilter;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    JdbcClient jdbcClient;
    @MockitoSpyBean
    TransactionEventPublisher transactionEventPublisher;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(requestIdFilter).build();
    }

    // --- idempotency ---

    @Test
    void a_retried_open_account_takes_effect_once_and_gets_the_same_answer() throws Exception {
        String key = newKey();
        String body = "{\"currency\":\"EUR\",\"label\":\"Merchant 42\"}";

        MockHttpServletResponse first = send(post("/v1/accounts"), key, body);
        long accounts = count("accounts");
        // Same body, written differently: still the same request
        MockHttpServletResponse retry = send(post("/v1/accounts"), key, "{ \"label\": \"Merchant 42\", \"currency\": \"EUR\" }");

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getHeader("Idempotent-Replayed")).isNull();
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getHeader("Location")).isEqualTo(first.getHeader("Location"));
        assertThat(read(retry)).isEqualTo(read(first));
        assertThat(count("accounts")).isEqualTo(accounts);
    }

    @Test
    void a_key_reused_for_a_different_request_is_refused_not_mistaken_for_a_retry() throws Exception {
        String key = newKey();
        send(post("/v1/accounts"), key, "{\"currency\":\"EUR\"}");

        MockHttpServletResponse other = send(post("/v1/accounts"), key, "{\"currency\":\"USD\"}");

        assertProblem(other, 422, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void a_retried_refusal_gets_the_same_refusal_even_if_it_would_now_succeed() throws Exception {
        String merchant = openAccount("EUR");
        String payouts = openAccount("EUR");
        fund(merchant, "10.00");
        String key = newKey();
        String payout = transfer(merchant, payouts, "50.00");

        MockHttpServletResponse refused = send(post("/v1/transactions"), key, payout);
        assertProblem(refused, 422, "OVERDRAFT_LIMIT_EXCEEDED");
        assertThat(read(refused).at("/errors/0/pointer").asString()).isEqualTo("/entries/0");

        fund(merchant, "100.00");
        MockHttpServletResponse retry = send(post("/v1/transactions"), key, payout);
        assertProblem(retry, 422, "OVERDRAFT_LIMIT_EXCEEDED");
        assertThat(retry.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getContentAsString()).isEqualTo(refused.getContentAsString());

        assertThat(send(post("/v1/transactions"), newKey(), payout).getStatus()).as("a new attempt, a new key").isEqualTo(201);
        assertThat(balance(merchant)).isEqualTo("60.00");
    }

    @Test
    void the_same_request_sent_many_times_at_once_is_recorded_once() throws Exception {
        String merchant = openAccount("EUR");
        String key = newKey();
        String body = transfer(SETTLEMENT_EUR, merchant, "5.00");

        List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            attempts.add(() -> send(post("/v1/transactions"), key, body));
        }
        List<MockHttpServletResponse> responses = concurrently(attempts);

        Set<String> bodies = new HashSet<>();
        for (MockHttpServletResponse r : responses) {
            assertThat(r.getStatus()).isEqualTo(201);
            bodies.add(read(r).toString());
        }
        assertThat(bodies).as("every attempt gets the same transaction").hasSize(1);
        assertThat(responses).filteredOn(r -> r.getHeader("Idempotent-Replayed") == null).hasSize(1);
        assertThat(balance(merchant)).isEqualTo("5.00");
    }

    @Test
    void a_request_that_crashed_halfway_left_nothing_and_its_retry_takes_effect() throws Exception {
        String merchant = openAccount("EUR");
        String key = newKey();
        String body = transfer(SETTLEMENT_EUR, merchant, "7.00");
        long transactions = count("transactions");

        // Fail after the transaction and balances were written, before commit
        // (stub the spy itself, not Spring's transactional proxy around it)
        TransactionEventPublisher spy = AopTestUtils.getUltimateTargetObject(transactionEventPublisher);
        doThrow(new IllegalStateException("simulated crash")).when(spy).publishTransactionRecorded(any());
        try {
            assertProblem(send(post("/v1/transactions"), key, body), 500, "INTERNAL_ERROR");
        } finally {
            doCallRealMethod().when(spy).publishTransactionRecorded(any());
        }
        assertThat(count("transactions")).isEqualTo(transactions);
        assertThat(balance(merchant)).isEqualTo("0.00");

        MockHttpServletResponse retry = send(post("/v1/transactions"), key, body);
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getHeader("Idempotent-Replayed")).as("the first attempt left nothing to replay").isNull();
        assertThat(balance(merchant)).isEqualTo("7.00");
    }

    // --- reversals ---

    @Test
    void reverse_once_then_conflict() throws Exception {
        String merchant = openAccount("EUR");
        JsonNode funding = read(send(post("/v1/transactions"), newKey(), transfer(SETTLEMENT_EUR, merchant, "3.00")));
        String id = funding.get("id").asString();

        MockHttpServletResponse reversal = send(post("/v1/transactions/" + id + "/reversal"), newKey(), null);
        assertThat(reversal.getStatus()).isEqualTo(201);
        assertThat(read(reversal).get("reversesTransactionId").asString()).isEqualTo(id);
        assertThat(read(mockMvc.perform(get("/v1/transactions/" + id)).andReturn().getResponse())
                .get("reversedByTransactionId").asString()).isEqualTo(read(reversal).get("id").asString());

        assertProblem(send(post("/v1/transactions/" + id + "/reversal"), newKey(), null), 409, "ALREADY_REVERSED");
        assertProblem(send(post("/v1/transactions/" + read(reversal).get("id").asString() + "/reversal"), newKey(), null),
                409, "REVERSAL_NOT_REVERSIBLE");
        assertProblem(send(post("/v1/transactions/" + UUID.randomUUID() + "/reversal"), newKey(), null), 404, "NOT_FOUND");
    }

    // --- errors say why, and where ---

    @Test
    void errors_are_problem_details_with_a_code_and_a_pointer() throws Exception {
        String eur = openAccount("EUR");
        String jpy = openAccount("JPY");

        assertProblem(send(post("/v1/accounts"), newKey(), "{\"currency\":\"EURO\"}"), 422, "INVALID_CURRENCY");
        assertProblem(send(post("/v1/accounts"), newKey(), "{\"currency\":\"ABC\"}"), 422, "INVALID_CURRENCY");
        assertProblem(send(post("/v1/accounts"), newKey(), "{\"currency\":\"GBP\"}"), 422, "UNSUPPORTED_CURRENCY");

        MockHttpServletResponse mixed = send(post("/v1/transactions"), newKey(), transfer(eur, jpy, "10"));
        assertProblem(mixed, 422, "CURRENCY_MISMATCH");
        assertThat(read(mixed).at("/errors/0/pointer").asString()).isEqualTo("/entries/1/accountId");

        MockHttpServletResponse unknown = send(post("/v1/transactions"), newKey(), transfer(eur, UUID.randomUUID().toString(), "1.00"));
        assertProblem(unknown, 422, "UNKNOWN_ACCOUNT");

        MockHttpServletResponse negative = send(post("/v1/transactions"), newKey(), transfer(eur, SETTLEMENT_EUR, "-1.00"));
        assertProblem(negative, 422, "AMOUNT_NOT_POSITIVE");

        MockHttpServletResponse notANumber = send(post("/v1/transactions"), newKey(), transfer(eur, SETTLEMENT_EUR, "1,00"));
        assertProblem(notANumber, 400, "VALIDATION_FAILED");
        assertThat(read(notANumber).at("/errors/0/pointer").asString()).startsWith("/entries/");

        assertProblem(send(post("/v1/transactions"), newKey(), "{\"description\":\"x\",\"entries\":[{\"accountId\":\""
                + eur + "\",\"direction\":\"SIDEWAYS\",\"amount\":\"1\"}]}"), 400, "MALFORMED_REQUEST");
        assertProblem(send(post("/v1/transactions"), newKey(), "{not json"), 400, "MALFORMED_REQUEST");
        assertProblem(send(post("/v1/accounts"), null, "{\"currency\":\"EUR\"}"), 400, "IDEMPOTENCY_KEY_MISSING");
        assertProblem(mockMvc.perform(get("/v1/accounts/" + UUID.randomUUID())).andReturn().getResponse(), 404, "NOT_FOUND");
        assertProblem(mockMvc.perform(get("/v1/accounts/not-a-uuid")).andReturn().getResponse(), 400, "MALFORMED_REQUEST");
    }

    @Test
    void the_request_id_is_echoed_and_in_problems() throws Exception {
        MockHttpServletResponse r = mockMvc.perform(get("/v1/accounts/" + UUID.randomUUID()).header("X-Request-Id", "trace-123"))
                .andReturn().getResponse();
        assertThat(r.getHeader("X-Request-Id")).isEqualTo("trace-123");
        assertThat(read(r).get("requestId").asString()).isEqualTo("trace-123");
        assertThat(mockMvc.perform(get("/v1/accounts/" + UUID.randomUUID())).andReturn().getResponse().getHeader("X-Request-Id"))
                .as("generated when absent").isNotBlank();
    }

    // --- reads ---

    @Test
    void entries_newest_first_with_running_balance_and_cursor() throws Exception {
        String merchant = openAccount("EUR");
        fund(merchant, "1.00");
        fund(merchant, "2.00");
        fund(merchant, "3.00");

        JsonNode page1 = read(mockMvc.perform(get("/v1/accounts/" + merchant + "/entries?limit=2")).andReturn().getResponse());
        assertThat(page1.at("/items/0/balanceAfter").asString()).isEqualTo("6.00");
        assertThat(page1.at("/items/1/balanceAfter").asString()).isEqualTo("3.00");
        JsonNode page2 = read(mockMvc.perform(get("/v1/accounts/" + merchant + "/entries?limit=2&cursor="
                + page1.get("nextCursor").asString())).andReturn().getResponse());
        assertThat(page2.at("/items/0/balanceAfter").asString()).isEqualTo("1.00");
        assertThat(page2.has("nextCursor")).isFalse();
        assertProblem(mockMvc.perform(get("/v1/accounts/" + merchant + "/entries?cursor=nope")).andReturn().getResponse(),
                400, "MALFORMED_REQUEST");

        JsonNode past = read(mockMvc.perform(get("/v1/accounts/" + merchant + "/balance?asOf=2000-01-01T00:00:00Z"))
                .andReturn().getResponse());
        assertThat(past.get("balance").asString()).isEqualTo("0.00");
    }

    // --- helpers ---

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String key, String body) throws Exception {
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private String openAccount(String currency) throws Exception {
        MockHttpServletResponse r = send(post("/v1/accounts"), newKey(), "{\"currency\":\"" + currency + "\"}");
        assertThat(r.getStatus()).isEqualTo(201);
        return read(r).get("id").asString();
    }

    private void fund(String account, String amount) throws Exception {
        assertThat(send(post("/v1/transactions"), newKey(), transfer(SETTLEMENT_EUR, account, amount)).getStatus())
                .isEqualTo(201);
    }

    private static String transfer(String from, String to, String amount) {
        return """
                {"description":"test","entries":[
                  {"accountId":"%s","direction":"DEBIT","amount":"%s"},
                  {"accountId":"%s","direction":"CREDIT","amount":"%s"}]}""".formatted(from, amount, to, amount);
    }

    private String balance(String account) throws Exception {
        return read(mockMvc.perform(get("/v1/accounts/" + account)).andReturn().getResponse()).get("balance").asString();
    }

    private JsonNode read(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString());
    }

    private void assertProblem(MockHttpServletResponse r, int status, String code) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(r.getContentType()).isEqualTo("application/problem+json");
        assertThat(read(r).get("code").asString()).isEqualTo(code);
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static <T> List<T> concurrently(List<Callable<T>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(work.size());
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
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }
}
