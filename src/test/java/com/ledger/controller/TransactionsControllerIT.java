package com.ledger.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.JsonNode;

/** {@code /v1/transactions}: recording (idempotent, also under concurrency and crashes), reading, reversing. */
class TransactionsControllerIT extends AbstractControllerIT {

    @MockitoSpyBean
    TransactionEventPublisher transactionEventPublisher;

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

    @Test
    void reverse_once_then_conflict() throws Exception {
        String merchant = openAccount("EUR");
        JsonNode funding = read(send(post("/v1/transactions"), newKey(), transfer(SETTLEMENT_EUR, merchant, "3.00")));
        String id = funding.get("id").asString();

        MockHttpServletResponse reversal = send(post("/v1/transactions/" + id + "/reversal"), newKey(), null);
        assertThat(reversal.getStatus()).isEqualTo(201);
        assertThat(read(reversal).get("reversesTransactionId").asString()).isEqualTo(id);
        assertThat(read(getResponse("/v1/transactions/" + id)).get("reversedByTransactionId").asString())
                .isEqualTo(read(reversal).get("id").asString());

        assertProblem(send(post("/v1/transactions/" + id + "/reversal"), newKey(), null), 409, "ALREADY_REVERSED");
        assertProblem(send(post("/v1/transactions/" + read(reversal).get("id").asString() + "/reversal"), newKey(), null),
                409, "REVERSAL_NOT_REVERSIBLE");
        assertProblem(send(post("/v1/transactions/" + UUID.randomUUID() + "/reversal"), newKey(), null), 404, "NOT_FOUND");
    }

    @Test
    void a_refused_transaction_leaves_nothing_behind() throws Exception {
        String merchant = openAccount("EUR");
        String payouts = openAccount("EUR");
        String jpyMerchant = openAccount("JPY");
        String jpyPayouts = openAccount("JPY");
        fund(merchant, "25.50");
        String reversed = read(send(post("/v1/transactions"), newKey(), transfer(SETTLEMENT_EUR, payouts, "1.00")))
                .get("id").asString();
        assertThat(send(post("/v1/transactions/" + reversed + "/reversal"), newKey(), null).getStatus()).isEqualTo(201);
        // Not the settlement account: payments from other tests may still be landing on it.
        List<String> involved = List.of(merchant, payouts, jpyMerchant, jpyPayouts);
        String before = ledgerState(involved);

        record Refusal(String path, String body, int status, String code) {
        }
        List<Refusal> refusals = List.of(
                new Refusal("/v1/transactions", """
                        {"description":"unbalanced","entries":[
                          {"accountId":"%s","direction":"DEBIT","amount":"100.00"},
                          {"accountId":"%s","direction":"CREDIT","amount":"99.00"}]}""".formatted(merchant, payouts),
                        422, "UNBALANCED"),
                new Refusal("/v1/transactions", transfer(merchant, payouts, "50.00"), 422, "OVERDRAFT_LIMIT_EXCEEDED"),
                new Refusal("/v1/transactions", transfer(jpyMerchant, jpyPayouts, "100.5"), 422, "AMOUNT_PRECISION_EXCEEDED"),
                new Refusal("/v1/transactions", transfer(merchant, jpyMerchant, "10.00"), 422, "CURRENCY_MISMATCH"),
                new Refusal("/v1/transactions", """
                        {"description":"one entry","entries":[
                          {"accountId":"%s","direction":"CREDIT","amount":"10.00"}]}""".formatted(merchant),
                        422, "TOO_FEW_ENTRIES"),
                new Refusal("/v1/transactions", transfer(merchant, UUID.randomUUID().toString(), "1.00"), 422, "UNKNOWN_ACCOUNT"),
                new Refusal("/v1/transactions", transfer(merchant, merchant, "1.00"), 422, "SAME_ACCOUNT"),
                new Refusal("/v1/transactions", transfer(merchant, payouts, "-1.00"), 422, "AMOUNT_NOT_POSITIVE"),
                new Refusal("/v1/transactions/" + reversed + "/reversal", null, 409, "ALREADY_REVERSED"),
                new Refusal("/v1/transactions/" + UUID.randomUUID() + "/reversal", null, 404, "NOT_FOUND"));

        for (Refusal refusal : refusals) {
            assertProblem(send(post(refusal.path()), newKey(), refusal.body()), refusal.status(), refusal.code());
            assertThat(ledgerState(involved)).as("after a %s refusal", refusal.code()).isEqualTo(before);
        }
    }

    /**
     * Everything a transaction on these accounts would write: their balances, their entries, the
     * transactions those belong to and the announcements naming them.
     */
    private String ledgerState(List<String> accounts) {
        String in = String.join(",", accounts.stream().map(a -> "'" + a + "'").toList());
        return "balances " + jdbcClient.sql("SELECT GROUP_CONCAT(balance ORDER BY id) FROM accounts WHERE id IN (" + in + ")")
                        .query(String.class).single()
                + ", entries " + jdbcClient.sql("SELECT COUNT(*) FROM entries WHERE account_id IN (" + in + ")")
                        .query(Long.class).single()
                + ", transactions " + jdbcClient.sql("SELECT COUNT(DISTINCT transaction_id) FROM entries WHERE account_id IN (" + in + ")")
                        .query(Long.class).single()
                + ", outbox " + jdbcClient.sql("SELECT COUNT(*) FROM outbox_events WHERE "
                                + String.join(" OR ", accounts.stream().map(a -> "payload LIKE '%" + a + "%'").toList()))
                        .query(Long.class).single();
    }

    @Test
    void errors_are_problem_details_with_a_code_and_a_pointer() throws Exception {
        String eur = openAccount("EUR");
        String jpy = openAccount("JPY");

        MockHttpServletResponse mixed = send(post("/v1/transactions"), newKey(), transfer(eur, jpy, "10"));
        assertProblem(mixed, 422, "CURRENCY_MISMATCH");
        assertThat(read(mixed).at("/errors/0/pointer").asString()).isEqualTo("/entries/1/accountId");

        MockHttpServletResponse sameAccount = send(post("/v1/transactions"), newKey(), transfer(eur, eur, "1.00"));
        assertProblem(sameAccount, 422, "SAME_ACCOUNT");
        assertThat(read(sameAccount).at("/errors/0/pointer").asString()).isEqualTo("/entries/1/accountId");

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
        assertProblem(getResponse("/v1/transactions/" + UUID.randomUUID()), 404, "NOT_FOUND");
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
