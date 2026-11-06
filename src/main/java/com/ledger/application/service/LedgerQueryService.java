package com.ledger.application.service;

import com.ledger.application.exception.NotFoundException;
import com.ledger.application.port.AccountRepository;
import com.ledger.application.port.LedgerClock;
import com.ledger.application.port.LedgerReadRepository.AccountEntryView;
import com.ledger.application.port.LedgerReadRepository;
import com.ledger.application.port.TransactionRepository;
import com.ledger.domain.model.Account;
import com.ledger.domain.model.Money;
import com.ledger.domain.model.Transaction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The ledger's read use cases. Plain consistent reads: they never take locks or block writers. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LedgerQueryService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerReadRepository ledgerReadRepository;
    private final LedgerClock ledgerClock;

    public Account getAccount(UUID id) {
        return accountRepository.find(id).orElseThrow(() -> new NotFoundException("Account", id));
    }

    public TransactionView getTransaction(UUID id) {
        Transaction transaction = transactionRepository.find(id).orElseThrow(() -> new NotFoundException("Transaction", id));
        return new TransactionView(transaction, transactionRepository.findReversalOf(id).orElse(null));
    }

    /** @param beforeEntryId the cursor: the id of the last entry of the previous page, or {@code null} */
    public EntryPage listEntries(UUID accountId, Long beforeEntryId, int limit) {
        getAccount(accountId);
        List<AccountEntryView> rows = ledgerReadRepository.findEntries(accountId, beforeEntryId, limit + 1);
        boolean more = rows.size() > limit;
        List<AccountEntryView> page = more ? rows.subList(0, limit) : rows;
        return new EntryPage(page, more ? page.get(page.size() - 1).entryId() : null);
    }

    /** @param asOf {@code null} for now */
    public BalanceView getBalance(UUID accountId, Instant asOf) {
        Account account = getAccount(accountId);
        if (asOf == null) {
            Money balance = new Money(account.balance(), account.currency());
            return new BalanceView(account, balance, ledgerClock.getCurrentTime());
        }
        Money zero = Money.zero(account.currency());
        return new BalanceView(account, ledgerReadRepository.findBalanceAsOf(accountId, zero, asOf).orElse(zero), asOf);
    }

    /** @param reversedBy the reversal of this transaction, or {@code null} */
    public record TransactionView(Transaction transaction, UUID reversedBy) {
    }

    /** @param nextCursor {@code null} on the last page */
    public record EntryPage(List<AccountEntryView> items, Long nextCursor) {
    }

    public record BalanceView(Account account, Money balance, Instant asOf) {
    }
}
