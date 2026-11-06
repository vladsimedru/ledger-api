package com.ledger.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.ledger.api.generated.model.FieldErrorDto;
import com.ledger.api.generated.model.ProblemDto;
import com.ledger.filter.RequestIdFilter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class ProblemFactoryTest {

    private final ProblemFactory problemFactory = new ProblemFactory(JsonMapper.builder().build());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void a_ledger_refusal_lists_every_violation_with_where_it_is() {
        LedgerRuleException e = new LedgerRuleException(List.of(
                new Violation("/entries/0", ErrorCode.OVERDRAFT_LIMIT_EXCEEDED, "Merchant 42 would go to -24.50"),
                new Violation("/entries/1", ErrorCode.UNKNOWN_ACCOUNT, "Account x doesn't exist")));

        ProblemDto problem = problemFactory.createRefusal(e);

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getTitle()).isEqualTo("Unprocessable Content");
        assertThat(problem.getCode()).isEqualTo("OVERDRAFT_LIMIT_EXCEEDED");
        assertThat(problem.getType()).isEqualTo("https://ledger.example/problems/overdraft-limit-exceeded");
        assertThat(problem.getDetail()).isEqualTo(e.getMessage());
        assertThat(problem.getErrors())
                .extracting(FieldErrorDto::getPointer, FieldErrorDto::getCode, FieldErrorDto::getDetail)
                .containsExactly(
                        tuple("/entries/0", "OVERDRAFT_LIMIT_EXCEEDED", "Merchant 42 would go to -24.50"),
                        tuple("/entries/1", "UNKNOWN_ACCOUNT", "Account x doesn't exist"));
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"ALREADY_REVERSED", "REVERSAL_NOT_REVERSIBLE"})
    void a_conflict_with_the_transactions_state_is_a_409(ErrorCode code) {
        assertThat(ProblemFactory.toHttpStatus(code)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problemFactory.createRefusal(new LedgerRuleException(null, code, "detail")).getStatus()).isEqualTo(409);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"ALREADY_REVERSED", "REVERSAL_NOT_REVERSIBLE"},
            mode = EnumSource.Mode.EXCLUDE)
    void every_other_refusal_is_a_422(ErrorCode code) {
        assertThat(ProblemFactory.toHttpStatus(code)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void a_missing_resource_is_a_404() {
        ProblemDto problem = problemFactory.createRefusal(new NotFoundException("Account", UUID.randomUUID()));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getCode()).isEqualTo(ApiErrorCode.NOT_FOUND);
        assertThat(problem.getType()).isEqualTo("https://ledger.example/problems/not-found");
        assertThat(problem.getErrors()).isNullOrEmpty();
    }

    @Test
    void the_problem_carries_the_request_id() {
        MDC.put(RequestIdFilter.MDC_KEY, "req-123");

        ProblemDto problem = problemFactory.createProblem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                "bad", List.of());

        assertThat(problem.getRequestId()).isEqualTo("req-123");
    }

    @Test
    void anything_else_is_not_a_refusal() {
        assertThatThrownBy(() -> problemFactory.createRefusal(new IllegalStateException("boom")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_json_is_the_problem_as_sent() {
        ProblemDto problem = problemFactory.createRefusal(new NotFoundException("Account", UUID.randomUUID()));

        assertThat(problemFactory.toJson(problem)).contains("\"status\":404").contains("\"code\":\"NOT_FOUND\"");
    }
}
