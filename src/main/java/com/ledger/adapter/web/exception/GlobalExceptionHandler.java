package com.ledger.adapter.web.exception;

import com.ledger.adapter.web.exception.BadRequestException;
import com.ledger.adapter.web.exception.IdempotencyKeyReusedException;
import com.ledger.adapter.web.exception.StoredProblemException;
import com.ledger.adapter.web.idempotency.IdempotencyService;
import com.ledger.api.generated.model.FieldErrorDto;
import com.ledger.api.generated.model.ProblemDto;
import com.ledger.application.exception.NotFoundException;
import com.ledger.domain.exception.LedgerRuleException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;

/** Every error leaves as {@code application/problem+json}, with a stable code and, where it can, a pointer. */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
class GlobalExceptionHandler {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private final ProblemFactory problemFactory;

    /** A refusal stored against an idempotency key: sent exactly as stored. */
    @ExceptionHandler
    ResponseEntity<String> stored(StoredProblemException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.getStatus()).contentType(PROBLEM_JSON);
        if (e.isReplayed()) {
            builder.header(IdempotencyService.REPLAYED_HEADER, "true");
        }
        return builder.body(e.getBody());
    }

    /** Refusals on endpoints without an idempotency key (reads). */
    @ExceptionHandler({LedgerRuleException.class, NotFoundException.class})
    ResponseEntity<ProblemDto> refusal(RuntimeException e) {
        return respond(problemFactory.createRefusal(e));
    }

    @ExceptionHandler
    ResponseEntity<ProblemDto> keyReused(IdempotencyKeyReusedException e) {
        return respond(problemFactory.createProblem(HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
                e.getMessage(), List.of()));
    }

    @ExceptionHandler
    ResponseEntity<ProblemDto> badRequest(BadRequestException e) {
        List<FieldErrorDto> errors = e.getPointer() == null ? List.of()
                : List.of(new FieldErrorDto(e.getCode(), e.getMessage()).pointer(e.getPointer()));
        return respond(problemFactory.createProblem(HttpStatus.BAD_REQUEST, e.getCode(), e.getMessage(), errors));
    }

    @ExceptionHandler
    ResponseEntity<ProblemDto> missingHeader(MissingRequestHeaderException e) {
        boolean idempotencyKey = "Idempotency-Key".equalsIgnoreCase(e.getHeaderName());
        return respond(problemFactory.createProblem(HttpStatus.BAD_REQUEST,
                idempotencyKey ? ApiErrorCode.IDEMPOTENCY_KEY_MISSING : ApiErrorCode.VALIDATION_FAILED,
                idempotencyKey ? "Every POST needs an Idempotency-Key header, e.g. a new UUID per request"
                        : "Missing header " + e.getHeaderName(), List.of()));
    }

    /** Malformed JSON, or a value of the wrong type (e.g. a direction that isn't DEBIT or CREDIT). */
    @ExceptionHandler
    ResponseEntity<ProblemDto> unreadable(HttpMessageNotReadableException e) {
        List<FieldErrorDto> errors = new ArrayList<>();
        String detail = "The request body is not valid JSON for this operation";
        if (e.getMostSpecificCause() instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
            StringBuilder pointer = new StringBuilder();
            for (JacksonException.Reference ref : jackson.getPath()) {
                pointer.append('/').append(ref.getPropertyName() != null ? ref.getPropertyName() : ref.getIndex());
            }
            detail = "Invalid value at " + pointer;
            errors.add(new FieldErrorDto(ApiErrorCode.MALFORMED_REQUEST, jackson.getOriginalMessage()).pointer(pointer.toString()));
        }
        return respond(problemFactory.createProblem(HttpStatus.BAD_REQUEST, ApiErrorCode.MALFORMED_REQUEST, detail, errors));
    }

    @ExceptionHandler
    ResponseEntity<ProblemDto> invalidBody(MethodArgumentNotValidException e) {
        return validationFailed(e.getFieldErrors().stream().map(GlobalExceptionHandler::fieldError).toList());
    }

    @ExceptionHandler
    ResponseEntity<ProblemDto> invalidArguments(HandlerMethodValidationException e) {
        List<FieldErrorDto> errors = new ArrayList<>();
        e.getParameterValidationResults().forEach(result -> {
            if (result instanceof org.springframework.validation.method.ParameterErrors body) {
                body.getFieldErrors().forEach(f -> errors.add(fieldError(f)));
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(err -> errors.add(new FieldErrorDto(ApiErrorCode.VALIDATION_FAILED,
                        name + ": " + err.getDefaultMessage())));
            }
        });
        return validationFailed(errors);
    }

    /** Raised instead of the above when validation runs through a method-validation proxy. */
    @ExceptionHandler
    ResponseEntity<ProblemDto> constraintViolations(ConstraintViolationException e) {
        return validationFailed(e.getConstraintViolations().stream()
                .map(v -> new FieldErrorDto(ApiErrorCode.VALIDATION_FAILED, v.getMessage()).pointer(pointer(v.getPropertyPath())))
                .toList());
    }

    @ExceptionHandler
    ResponseEntity<ProblemDto> typeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(problemFactory.createProblem(HttpStatus.BAD_REQUEST, ApiErrorCode.MALFORMED_REQUEST,
                "'" + e.getValue() + "' is not a valid " + e.getName(), List.of()));
    }

    @ExceptionHandler
    ResponseEntity<ProblemDto> other(Exception e) {
        if (e instanceof ErrorResponse spring && spring.getStatusCode().is4xxClientError()) {
            // Spring MVC's own client errors: unknown path, wrong method, wrong content type...
            HttpStatusCode status = spring.getStatusCode();
            String code = status.value() == 404 ? ApiErrorCode.NOT_FOUND : ApiErrorCode.MALFORMED_REQUEST;
            ResponseEntity<ProblemDto> response = respond(problemFactory.createProblem(HttpStatus.valueOf(status.value()), code,
                    spring.getBody().getDetail(), List.of()));
            HttpHeaders headers = new HttpHeaders();
            headers.addAll(spring.getHeaders());
            headers.addAll(response.getHeaders());
            return new ResponseEntity<>(response.getBody(), headers, status);
        }
        log.error("Unexpected error; nothing was recorded", e);
        return respond(problemFactory.createProblem(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                "Something went wrong and nothing was recorded. It is safe to retry with the same Idempotency-Key.",
                List.of()));
    }

    private ResponseEntity<ProblemDto> validationFailed(List<FieldErrorDto> errors) {
        String detail = errors.isEmpty() ? "The request is invalid"
                : errors.get(0).getPointer() == null ? errors.get(0).getDetail()
                : errors.get(0).getPointer() + ": " + errors.get(0).getDetail();
        return respond(problemFactory.createProblem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, detail, errors));
    }

    private static ResponseEntity<ProblemDto> respond(ProblemDto problem) {
        return ResponseEntity.status(problem.getStatus()).contentType(PROBLEM_JSON).body(problem);
    }

    private static FieldErrorDto fieldError(FieldError f) {
        return new FieldErrorDto(ApiErrorCode.VALIDATION_FAILED, f.getDefaultMessage()).pointer(pointer(f.getField()));
    }

    /** {@code entries[1].amount} to {@code /entries/1/amount}. */
    static String pointer(String fieldPath) {
        return "/" + fieldPath.replaceAll("\\[(\\d+)]", ".$1").replace('.', '/');
    }

    /** A Bean Validation path, minus the method and parameter nodes, as a JSON Pointer into the body. */
    private static String pointer(Path path) {
        StringBuilder pointer = new StringBuilder();
        for (Path.Node node : path) {
            if (node.getKind() == jakarta.validation.ElementKind.PROPERTY) {
                if (node.getIndex() != null) {
                    pointer.append('/').append(node.getIndex());
                }
                pointer.append('/').append(node.getName());
            } else if (node.getKind() == jakarta.validation.ElementKind.CONTAINER_ELEMENT && node.getIndex() != null) {
                pointer.append('/').append(node.getIndex());
            }
        }
        return pointer.isEmpty() ? null : pointer.toString();
    }
}
