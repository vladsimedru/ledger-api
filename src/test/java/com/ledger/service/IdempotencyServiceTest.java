package com.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ledger.api.generated.model.AccountDto;
import com.ledger.api.generated.model.OpenAccountRequestDto;
import com.ledger.exception.ErrorCode;
import com.ledger.exception.IdempotencyKeyReusedException;
import com.ledger.exception.LedgerRuleException;
import com.ledger.exception.NotFoundException;
import com.ledger.exception.ProblemFactory;
import com.ledger.exception.StoredProblemException;
import com.ledger.repository.IdempotencyKeyRepository;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Retries, key reuse and stored refusals, against an in-memory key store. */
class IdempotencyServiceTest {

    private static final String KEY = "3f1c6a52-1d3e-4a8e-9f0a-6f4f2a1b7c90";
    private static final AccountDto ACCOUNT = new AccountDto(UUID.fromString("01960000-0000-7000-8000-000000000042"),
            "EUR", "0.00", OffsetDateTime.parse("2026-11-04T10:00:00Z"));
    private static final String LOCATION = "/v1/accounts/" + ACCOUNT.getId();

    private final InMemoryIdempotencyKeyRepository idempotencyKeyRepository = new InMemoryIdempotencyKeyRepository();
    private final MockHttpServletRequest httpServletRequest = new MockHttpServletRequest("POST", "/v1/accounts");
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final IdempotencyService idempotencyService = new IdempotencyService(idempotencyKeyRepository,
            transactionTemplate(), new ProblemFactory(jsonMapper), jsonMapper, httpServletRequest);
    private final AtomicInteger timesWorkRan = new AtomicInteger();

    @Test
    void a_new_key_does_the_work_and_stores_its_response() {
        ResponseEntity<AccountDto> response = execute(request("EUR"), openAccount());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(ACCOUNT);
        assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(LOCATION));
        assertThat(response.getHeaders().containsHeader(IdempotencyService.REPLAYED_HEADER)).isFalse();
        assertThat(timesWorkRan).hasValue(1);

        IdempotencyKeyRepository.StoredResponse stored = idempotencyKeyRepository.completed.get(KEY);
        assertThat(stored.status()).isEqualTo(201);
        assertThat(stored.location()).isEqualTo(LOCATION);
        assertThat(jsonMapper.readValue(stored.body(), AccountDto.class)).isEqualTo(ACCOUNT);
    }

    @Test
    void a_retry_gets_the_stored_response_back_without_doing_the_work_again() {
        execute(request("EUR"), openAccount());

        // An equal body in a new object: what a client resending the request produces
        ResponseEntity<AccountDto> retry = execute(request("EUR"), openAccount());

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isEqualTo(ACCOUNT);
        assertThat(retry.getHeaders().getLocation()).isEqualTo(URI.create(LOCATION));
        assertThat(retry.getHeaders().getFirst(IdempotencyService.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(timesWorkRan).hasValue(1);
    }

    @Test
    void the_same_key_with_a_different_body_is_refused() {
        execute(request("EUR"), openAccount());

        assertThatThrownBy(() -> execute(request("USD"), openAccount()))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(timesWorkRan).hasValue(1);
    }

    @Test
    void the_same_key_on_a_different_endpoint_is_refused() {
        execute(request("EUR"), openAccount());

        httpServletRequest.setRequestURI("/v1/transactions");
        assertThatThrownBy(() -> execute(request("EUR"), openAccount()))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(timesWorkRan).hasValue(1);
    }

    @Test
    void a_ledger_refusal_is_stored_and_a_retry_gets_the_same_refusal() {
        Supplier<ResponseEntity<AccountDto>> refused = failingWith(
                new LedgerRuleException(null, ErrorCode.ALREADY_REVERSED, "Transaction x has already been reversed"));

        assertThatThrownBy(() -> execute(request("EUR"), refused))
                .isInstanceOfSatisfying(StoredProblemException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getBody()).contains("\"code\":\"ALREADY_REVERSED\"");
                    assertThat(e.isReplayed()).isFalse();
                });
        assertThatThrownBy(() -> execute(request("EUR"), refused))
                .isInstanceOfSatisfying(StoredProblemException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getBody()).contains("\"code\":\"ALREADY_REVERSED\"");
                    assertThat(e.isReplayed()).isTrue();
                });
        assertThat(timesWorkRan).hasValue(1);
    }

    @Test
    void a_missing_resource_is_stored_as_a_404() {
        Supplier<ResponseEntity<AccountDto>> notFound = failingWith(new NotFoundException("Transaction", UUID.randomUUID()));

        assertThatThrownBy(() -> execute(request("EUR"), notFound))
                .isInstanceOfSatisfying(StoredProblemException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThat(idempotencyKeyRepository.completed.get(KEY).status()).isEqualTo(404);
    }

    @Test
    void an_unexpected_failure_stores_nothing_so_a_retry_does_the_work_afresh() {
        assertThatThrownBy(() -> execute(request("EUR"), failingWith(new IllegalStateException("database went away"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(idempotencyKeyRepository.completed).isEmpty();

        ResponseEntity<AccountDto> retry = execute(request("EUR"), openAccount());

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().containsHeader(IdempotencyService.REPLAYED_HEADER)).isFalse();
        assertThat(timesWorkRan).hasValue(2);
    }

    private ResponseEntity<AccountDto> execute(OpenAccountRequestDto request, Supplier<ResponseEntity<AccountDto>> work) {
        return idempotencyService.execute(KEY, request, AccountDto.class, work);
    }

    private static OpenAccountRequestDto request(String currency) {
        return new OpenAccountRequestDto(currency).label("Merchant 42");
    }

    private Supplier<ResponseEntity<AccountDto>> openAccount() {
        return () -> {
            timesWorkRan.incrementAndGet();
            return ResponseEntity.created(URI.create(LOCATION)).body(ACCOUNT);
        };
    }

    private Supplier<ResponseEntity<AccountDto>> failingWith(RuntimeException e) {
        return () -> {
            timesWorkRan.incrementAndGet();
            throw e;
        };
    }

    /** Runs the callback directly: the in-memory store needs no transaction. */
    @SuppressWarnings("unchecked")
    private static TransactionTemplate transactionTemplate() {
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        return transactionTemplate;
    }

    /**
     * Keeps completed keys only. An unfinished claim leaves nothing behind, as a rolled-back claim
     * does in the database.
     */
    private static final class InMemoryIdempotencyKeyRepository implements IdempotencyKeyRepository {

        private final Map<String, StoredResponse> completed = new HashMap<>();

        @Override
        public Optional<StoredResponse> claim(String key, String requestHash) {
            return Optional.ofNullable(completed.get(key));
        }

        @Override
        public void complete(String key, StoredResponse response) {
            completed.put(key, response);
        }

        @Override
        public int deleteOlderThan(Duration retention, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
