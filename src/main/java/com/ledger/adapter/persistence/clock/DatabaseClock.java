package com.ledger.adapter.persistence.clock;

import com.ledger.application.port.LedgerClock;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads the time from MySQL rather than from the instance: instances' clocks drift apart, the
 * database's is one clock. Called after the accounts are locked, so an account's entries get
 * non-decreasing timestamps in the order they're applied.
 */
@Component
@RequiredArgsConstructor
class DatabaseClock implements LedgerClock {

    private final JdbcClient jdbcClient;

    @Override
    public Instant getCurrentTime() {
        return jdbcClient.sql("SELECT CURRENT_TIMESTAMP(6)").query(Timestamp.class).single().toInstant();
    }
}
