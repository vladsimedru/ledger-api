package com.ledger.service;

import com.ledger.api.generated.model.ProblemDto;
import com.ledger.exception.IdempotencyKeyReusedException;
import com.ledger.exception.LedgerRuleException;
import com.ledger.exception.NotFoundException;
import com.ledger.exception.ProblemFactory;
import com.ledger.exception.StoredProblemException;
import com.ledger.repository.IdempotencyKeyRepository.StoredResponse;
import com.ledger.repository.IdempotencyKeyRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes a POST safe to retry. The key is claimed, the work is done and its response is stored, all in
 * one database transaction:
 * <ul>
 *   <li>commits: the work and its stored response exist together; a retry gets that response back,
 *       on any instance, without doing the work again;</li>
 *   <li>refused by a ledger rule: nothing but the stored refusal is written; a retry gets the same refusal;</li>
 *   <li>crashes or fails unexpectedly: everything rolls back, the key included; a retry does the work afresh;</li>
 *   <li>a retry arriving while the first attempt is still running waits for it, then gets its response.</li>
 * </ul>
 * A request is a retry only if its method, path and body match the original: the ledger never
 * mistakes a different request for a retry.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyService {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final TransactionTemplate transactionTemplate;
    private final ProblemFactory problemFactory;
    private final JsonMapper jsonMapper;
    private final HttpServletRequest httpServletRequest;

    /**
     * @param requestBody the parsed request body, hashed (re-serialised, so whitespace and field order
     *                    don't matter) to tell a retry from a different request
     * @param work        does the POST and returns its response; its status, body and {@code Location}
     *                    are stored, and a retry gets them back
     */
    public <T> ResponseEntity<T> execute(String key, Object requestBody, Class<T> responseType,
                                         Supplier<ResponseEntity<T>> work) {
        String hash = hash(httpServletRequest.getMethod(), httpServletRequest.getRequestURI(),
                jsonMapper.writeValueAsString(requestBody));

        Outcome<T> outcome = transactionTemplate.execute(tx -> {
            var stored = idempotencyKeyRepository.claim(key, hash);
            if (stored.isPresent()) {
                if (!stored.get().requestHash().equals(hash)) {
                    throw new IdempotencyKeyReusedException(key);
                }
                return new Outcome<>(stored.get(), null, true);
            }
            try {
                ResponseEntity<T> fresh = work.get();
                URI location = fresh.getHeaders().getLocation();
                StoredResponse response = new StoredResponse(hash, fresh.getStatusCode().value(),
                        jsonMapper.writeValueAsString(fresh.getBody()), location == null ? null : location.toString());
                idempotencyKeyRepository.complete(key, response);
                return new Outcome<>(response, fresh, false);
            } catch (LedgerRuleException | NotFoundException refusal) {
                // Refusals happen before any write, and don't mark the transaction rollback-only:
                // committing now stores just the refusal.
                ProblemDto problem = problemFactory.createRefusal(refusal);
                StoredResponse response = new StoredResponse(hash, problem.getStatus(), problemFactory.toJson(problem), null);
                idempotencyKeyRepository.complete(key, response);
                return new Outcome<>(response, null, false);
            }
        });

        if (outcome.fresh() != null) {
            return outcome.fresh();
        }
        StoredResponse response = outcome.response();
        if (!HttpStatusCode.valueOf(response.status()).is2xxSuccessful()) {
            throw new StoredProblemException(response.status(), response.body(), outcome.replayed());
        }
        // A retry of a request that succeeded: rebuild its response from what was stored
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status()).header(REPLAYED_HEADER, "true");
        if (response.location() != null) {
            builder.location(URI.create(response.location()));
        }
        return builder.body(jsonMapper.readValue(response.body(), responseType));
    }

    /** @param fresh the response the work just returned; {@code null} for a retry or a refusal */
    private record Outcome<T>(StoredResponse response, ResponseEntity<T> fresh, boolean replayed) {
    }

    private static String hash(String method, String path, String body) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((method + ' ' + path + '\n' + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
