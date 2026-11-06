package com.ledger.adapter.persistence.repository;

import com.ledger.application.port.LedgerReadRepository;
import com.ledger.domain.model.Direction;
import com.ledger.domain.model.LedgerCurrency;
import com.ledger.domain.model.Money;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class LedgerReadRepositoryImpl implements LedgerReadRepository {

    private final JdbcClient jdbcClient;

    @Override
    public List<AccountEntryView> findEntries(UUID accountId, Long beforeEntryId, int limit) {
        // Keyset pagination on the entry id: stable under concurrent inserts, unlike OFFSET.
        return jdbcClient.sql("""
                        SELECT e.id, e.transaction_id, t.description, t.currency, e.direction, e.amount,
                               e.balance_after, e.recorded_at
                        FROM entries e JOIN transactions t ON t.id = e.transaction_id
                        WHERE e.account_id = :accountId AND e.id < :before
                        ORDER BY e.id DESC
                        LIMIT :limit""")
                .param("accountId", accountId.toString())
                .param("before", beforeEntryId == null ? Long.MAX_VALUE : beforeEntryId)
                .param("limit", limit)
                .query((rs, n) -> {
                    LedgerCurrency c = LedgerCurrency.valueOf(rs.getString("currency"));
                    return new AccountEntryView(rs.getLong("id"), UUID.fromString(rs.getString("transaction_id")),
                            rs.getString("description"), Direction.valueOf(rs.getString("direction")),
                            new Money(rs.getLong("amount"), c), new Money(rs.getLong("balance_after"), c),
                            rs.getTimestamp("recorded_at").toInstant());
                })
                .list();
    }

    @Override
    public Optional<Money> findBalanceAsOf(UUID accountId, Money zero, Instant asOf) {
        return jdbcClient.sql("""
                        SELECT balance_after FROM entries
                        WHERE account_id = :accountId AND recorded_at <= :asOf
                        ORDER BY recorded_at DESC, id DESC
                        LIMIT 1""")
                .param("accountId", accountId.toString())
                .param("asOf", Timestamp.from(asOf))
                .query(Long.class)
                .optional()
                .map(minor -> new Money(minor, zero.currency()));
    }
}
