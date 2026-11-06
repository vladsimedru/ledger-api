package com.ledger.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** {@code /v1/accounts}: opening accounts (idempotent), reading them, their entries and balances. */
class AccountsControllerIT extends AbstractControllerIT {

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
    void errors_are_problem_details_with_a_code() throws Exception {
        assertProblem(send(post("/v1/accounts"), newKey(), "{\"currency\":\"EURO\"}"), 422, "INVALID_CURRENCY");
        assertProblem(send(post("/v1/accounts"), newKey(), "{\"currency\":\"ABC\"}"), 422, "INVALID_CURRENCY");
        assertProblem(send(post("/v1/accounts"), newKey(), "{\"currency\":\"GBP\"}"), 422, "UNSUPPORTED_CURRENCY");
        assertProblem(send(post("/v1/accounts"), null, "{\"currency\":\"EUR\"}"), 400, "IDEMPOTENCY_KEY_MISSING");
        assertProblem(getResponse("/v1/accounts/" + UUID.randomUUID()), 404, "NOT_FOUND");
        assertProblem(getResponse("/v1/accounts/not-a-uuid"), 400, "MALFORMED_REQUEST");
    }

    @Test
    void the_request_id_is_echoed_and_in_problems() throws Exception {
        MockHttpServletResponse r = mockMvc.perform(get("/v1/accounts/" + UUID.randomUUID()).header("X-Request-Id", "trace-123"))
                .andReturn().getResponse();
        assertThat(r.getHeader("X-Request-Id")).isEqualTo("trace-123");
        assertThat(read(r).get("requestId").asString()).isEqualTo("trace-123");
        assertThat(getResponse("/v1/accounts/" + UUID.randomUUID()).getHeader("X-Request-Id"))
                .as("generated when absent").isNotBlank();
    }

    @Test
    void entries_newest_first_with_running_balance_and_cursor() throws Exception {
        String merchant = openAccount("EUR");
        fund(merchant, "1.00");
        fund(merchant, "2.00");
        fund(merchant, "3.00");

        JsonNode page1 = read(getResponse("/v1/accounts/" + merchant + "/entries?limit=2"));
        assertThat(page1.at("/items/0/balanceAfter").asString()).isEqualTo("6.00");
        assertThat(page1.at("/items/1/balanceAfter").asString()).isEqualTo("3.00");
        JsonNode page2 = read(getResponse("/v1/accounts/" + merchant + "/entries?limit=2&cursor="
                + page1.get("nextCursor").asString()));
        assertThat(page2.at("/items/0/balanceAfter").asString()).isEqualTo("1.00");
        assertThat(page2.has("nextCursor")).isFalse();
        assertProblem(getResponse("/v1/accounts/" + merchant + "/entries?cursor=nope"), 400, "MALFORMED_REQUEST");
    }

    @Test
    void balance_now_and_as_at_a_past_instant() throws Exception {
        String merchant = openAccount("EUR");
        fund(merchant, "4.00");

        assertThat(read(getResponse("/v1/accounts/" + merchant + "/balance")).get("balance").asString()).isEqualTo("4.00");
        JsonNode past = read(getResponse("/v1/accounts/" + merchant + "/balance?asOf=2000-01-01T00:00:00Z"));
        assertThat(past.get("balance").asString()).isEqualTo("0.00");
    }
}
