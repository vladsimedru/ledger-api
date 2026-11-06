package com.ledger.scheduler;

import com.ledger.repository.IdempotencyKeyRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes idempotency keys once they are past the retention period (the API promises at least 24 hours),
 * so the table doesn't grow forever. Safe to run on every instance at once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class IdempotencyKeyCleanupScheduler {

    private static final int BATCH_SIZE = 1000;

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    @Value("${ledger.idempotency.retention}")
    private final Duration retention;

    @Scheduled(fixedDelayString = "${ledger.idempotency.cleanup-interval:PT10M}", initialDelayString = "PT1M")
    void deleteExpiredKeys() {
        int deleted;
        do {
            // In batches, so no single statement holds locks on many rows.
            deleted = idempotencyKeyRepository.deleteOlderThan(retention, BATCH_SIZE);
            if (deleted > 0) {
                log.info("Deleted {} idempotency keys older than {}", deleted, retention);
            }
        } while (deleted == BATCH_SIZE);
    }
}
