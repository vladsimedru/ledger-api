package com.ledger.repository;

import com.ledger.model.AccountEntryView;
import com.ledger.model.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Queries that read across transactions and entries; never locks anything. */
public interface LedgerReadRepository {

    /** One page of an account's entries, newest first, starting after {@code beforeEntryId} if given. */
    List<AccountEntryView> findEntries(UUID accountId, Long beforeEntryId, int limit);

    /** The balance after the account's last entry recorded at or before {@code asOf}; empty if there is none. */
    Optional<Money> findBalanceAsOf(UUID accountId, Money zero, Instant asOf);
}
