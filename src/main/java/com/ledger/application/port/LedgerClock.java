package com.ledger.application.port;

import java.time.Instant;

/**
 * The time transactions are recorded at. One clock for every instance (the database's), so that
 * an account's entries are recorded at non-decreasing times whichever instance records them,
 * which "the balance as at an instant" relies on.
 */
public interface LedgerClock {

    Instant getCurrentTime();
}
