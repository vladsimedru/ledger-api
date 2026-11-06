package com.ledger.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ledger.TestcontainersConfiguration;
import com.ledger.filter.RequestIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The REST API end to end against a real MySQL. Shared setup and helpers for the controller tests. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
abstract class AbstractControllerIT {

    static final String SETTLEMENT_EUR = "00000000-0000-0000-0000-000000000978";

    @Autowired
    WebApplicationContext webApplicationContext;
    @Autowired
    RequestIdFilter requestIdFilter;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    JdbcClient jdbcClient;

    MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(requestIdFilter).build();
    }

    MockHttpServletResponse send(MockHttpServletRequestBuilder request, String key, String body) throws Exception {
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    MockHttpServletResponse getResponse(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse();
    }

    String openAccount(String currency) throws Exception {
        MockHttpServletResponse r = send(post("/v1/accounts"), newKey(), "{\"currency\":\"" + currency + "\"}");
        assertThat(r.getStatus()).isEqualTo(201);
        return read(r).get("id").asString();
    }

    void fund(String account, String amount) throws Exception {
        assertThat(send(post("/v1/transactions"), newKey(), transfer(SETTLEMENT_EUR, account, amount)).getStatus())
                .isEqualTo(201);
    }

    static String transfer(String from, String to, String amount) {
        return """
                {"description":"test","entries":[
                  {"accountId":"%s","direction":"DEBIT","amount":"%s"},
                  {"accountId":"%s","direction":"CREDIT","amount":"%s"}]}""".formatted(from, amount, to, amount);
    }

    String balance(String account) throws Exception {
        return read(getResponse("/v1/accounts/" + account)).get("balance").asString();
    }

    JsonNode read(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString());
    }

    void assertProblem(MockHttpServletResponse r, int status, String code) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(r.getContentType()).isEqualTo("application/problem+json");
        assertThat(read(r).get("code").asString()).isEqualTo(code);
    }

    long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }
}
