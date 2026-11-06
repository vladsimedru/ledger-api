package com.ledger.adapter.persistence.repository;

import com.ledger.application.port.AccountRepository;
import com.ledger.domain.model.Account;
import com.ledger.domain.model.LedgerCurrency;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
class AccountRepositoryImpl implements AccountRepository {

    private final JdbcAggregateTemplate jdbcAggregateTemplate;
    private final JdbcClient jdbcClient;

    @Override
    public void insert(Account account) {
        jdbcAggregateTemplate.insert(account);
    }

    @Override
    public Optional<Account> find(UUID id) {
        return Optional.ofNullable(jdbcAggregateTemplate.findById(id, Account.class));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Account> lockAll(Collection<UUID> ids) {
        // One row at a time, in ascending id order: a single "WHERE id IN (...) FOR UPDATE" would leave
        // the order to the query plan. A fixed global order is what rules out deadlocks between
        // transactions that touch the same accounts (A -> B and B -> A at the same time).
        List<String> sorted = ids.stream().map(UUID::toString).sorted().toList();
        Map<UUID, Account> locked = new LinkedHashMap<>();
        for (String id : sorted) {
            jdbcClient.sql("""
                            SELECT id, currency, overdraft_limit, balance, label, created_at
                            FROM accounts WHERE id = :id FOR UPDATE""")
                    .param("id", id)
                    .query((rs, n) -> toAccount(rs))
                    .optional()
                    .ifPresent(a -> locked.put(a.id(), a));
        }
        return locked;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateBalances(List<Account> accounts) {
        for (Account a : accounts) {
            int updated = jdbcClient.sql("UPDATE accounts SET balance = :balance WHERE id = :id")
                    .param("balance", a.balance())
                    .param("id", a.id().toString())
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("account " + a.id() + " vanished while locked");
            }
        }
    }

    private static Account toAccount(ResultSet rs) throws SQLException {
        return new Account(UUID.fromString(rs.getString("id")), LedgerCurrency.valueOf(rs.getString("currency")),
                rs.getObject("overdraft_limit", Long.class), rs.getLong("balance"), rs.getString("label"),
                rs.getTimestamp("created_at").toInstant());
    }
}
