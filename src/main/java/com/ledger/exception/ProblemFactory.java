package com.ledger.exception;

import com.ledger.api.generated.model.FieldErrorDto;
import com.ledger.api.generated.model.ProblemDto;
import com.ledger.filter.RequestIdFilter;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Builds RFC 9457 problem details, the body of every error response. */
@Component
@RequiredArgsConstructor
public class ProblemFactory {

    private static final String TYPE_BASE = "https://ledger.example/problems/";

    private final JsonMapper jsonMapper;

    /** The status and body for a refusal by the ledger: what gets stored against an idempotency key. */
    public ProblemDto createRefusal(RuntimeException e) {
        if (e instanceof LedgerRuleException rule) {
            List<FieldErrorDto> errors = rule.getViolations().stream()
                    .map(v -> new FieldErrorDto(v.code().name(), v.detail()).pointer(v.pointer()))
                    .toList();
            return createProblem(toHttpStatus(rule.code()), rule.code().name(), rule.getMessage(), errors);
        }
        if (e instanceof NotFoundException) {
            return createProblem(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, e.getMessage(), List.of());
        }
        throw new IllegalArgumentException("not a refusal", e);
    }

    public ProblemDto createProblem(HttpStatus status, String code, String detail, List<FieldErrorDto> errors) {
        ProblemDto p = new ProblemDto(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                status.getReasonPhrase(), status.value(), code)
                .detail(detail)
                .requestId(MDC.get(RequestIdFilter.MDC_KEY));
        if (!errors.isEmpty()) {
            p.errors(errors);
        }
        return p;
    }

    public String toJson(ProblemDto problem) {
        return jsonMapper.writeValueAsString(problem);
    }

    public static HttpStatus toHttpStatus(ErrorCode code) {
        return switch (code) {
            case ALREADY_REVERSED, REVERSAL_NOT_REVERSIBLE -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }
}
