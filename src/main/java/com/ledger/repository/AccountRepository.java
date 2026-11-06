package com.ledger.repository;

import com.ledger.entity.Account;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

    void insert(Account account);

    Optional<Account> find(UUID id);

    /**
     * Locks the existing accounts among {@code ids} for the rest of the current database transaction
     * and returns their current state. Locks are taken one account at a time in ascending id order,
     * the same order in every transaction, so two transactions can never each hold a lock the other
     * one waits for. Ids that don't exist are simply absent from the result.
     */
    Map<UUID, Account> lockAll(Collection<UUID> ids);

    /** Writes new balances. Only call with accounts locked by {@link #lockAll} in this transaction. */
    void updateBalances(List<Account> accounts);
}
