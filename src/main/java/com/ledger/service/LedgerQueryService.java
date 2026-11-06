package com.ledger.service;

import com.ledger.api.generated.model.AccountDto;
import com.ledger.api.generated.model.AccountEntryPageDto;
import com.ledger.api.generated.model.BalanceDto;
import com.ledger.api.generated.model.TransactionDto;
import com.ledger.entity.Account;
import com.ledger.entity.Transaction;
import com.ledger.exception.NotFoundException;
import com.ledger.mapper.AccountMapper;
import com.ledger.mapper.TransactionMapper;
import com.ledger.model.AccountEntryView;
import com.ledger.model.BalanceView;
import com.ledger.model.EntryPage;
import com.ledger.model.Money;
import com.ledger.model.TransactionView;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.LedgerReadRepository;
import com.ledger.repository.TransactionRepository;
import com.ledger.support.LedgerClock;
import java.time.Instant;
import java.time.OffsetDateTime;
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

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerReadRepository ledgerReadRepository;
    private final LedgerClock ledgerClock;
    private final AccountMapper accountMapper;
    private final TransactionMapper transactionMapper;

    public AccountDto getAccount(UUID id) {
        return accountMapper.toAccountDto(findAccount(id));
    }

    /** The transaction, with the reversal that reversed it, if any. */
    public TransactionDto getTransaction(UUID id) {
        Transaction transaction = transactionRepository.find(id).orElseThrow(() -> new NotFoundException("Transaction", id));
        UUID reversedBy = transactionRepository.findReversalOf(id).orElse(null);
        return transactionMapper.toTransactionDto(new TransactionView(transaction, reversedBy));
    }

    /**
     * One page of an account's entries, newest first.
     *
     * @param cursor the {@code nextCursor} of the previous page, or {@code null} for the first page
     * @param limit  the page size, or {@code null} for the default of 50
     */
    public AccountEntryPageDto listEntries(UUID accountId, String cursor, Integer limit) {
        Long beforeEntryId = accountMapper.decodeCursor(cursor);
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        findAccount(accountId);
        List<AccountEntryView> rows = ledgerReadRepository.findEntries(accountId, beforeEntryId, pageSize + 1);
        boolean more = rows.size() > pageSize;
        List<AccountEntryView> page = more ? rows.subList(0, pageSize) : rows;
        Long nextCursor = more ? page.get(page.size() - 1).entryId() : null;
        return accountMapper.toAccountEntryPageDto(new EntryPage(page, nextCursor));
    }

    /** @param asOf {@code null} for now */
    public BalanceDto getBalance(UUID accountId, OffsetDateTime asOf) {
        Account account = findAccount(accountId);
        if (asOf == null) {
            Money balance = new Money(account.balance(), account.currency());
            return accountMapper.toBalanceDto(new BalanceView(account, balance, ledgerClock.getCurrentTime()));
        }
        Instant at = asOf.toInstant();
        Money zero = Money.zero(account.currency());
        Money balance = ledgerReadRepository.findBalanceAsOf(accountId, zero, at).orElse(zero);
        return accountMapper.toBalanceDto(new BalanceView(account, balance, at));
    }

    private Account findAccount(UUID id) {
        return accountRepository.find(id).orElseThrow(() -> new NotFoundException("Account", id));
    }
}
