package com.ledger.service;

import com.ledger.api.generated.model.AccountDto;
import com.ledger.api.generated.model.OpenAccountRequestDto;
import com.ledger.api.generated.model.RecordTransactionRequestDto;
import com.ledger.api.generated.model.ReverseTransactionRequestDto;
import com.ledger.api.generated.model.TransactionDto;
import com.ledger.entity.Account;
import com.ledger.entity.Transaction;
import com.ledger.exception.ErrorCode;
import com.ledger.exception.LedgerRuleException;
import com.ledger.exception.NotFoundException;
import com.ledger.mapper.AccountMapper;
import com.ledger.mapper.TransactionMapper;
import com.ledger.messaging.TransactionEventPublisher;
import com.ledger.model.Direction;
import com.ledger.model.EntryDraft;
import com.ledger.model.LedgerCurrency;
import com.ledger.model.Money;
import com.ledger.model.Posting;
import com.ledger.model.SettlementAccounts;
import com.ledger.model.TransactionDraft;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.TransactionRepository;
import com.ledger.support.LedgerClock;
import com.ledger.support.UuidV7;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's write use cases. Each runs in one database transaction: it all commits or none of it does.
 *
 * <p>Refusals ({@link LedgerRuleException}, {@link NotFoundException}) are always raised <em>before</em>
 * the first write, so they leave nothing behind, and they don't mark the transaction rollback-only. That
 * lets a caller that joins the same transaction (the idempotency layer) still commit a record of the
 * refusal, so that a retried request gets the same answer.
 */
@Service
@Transactional(noRollbackFor = {LedgerRuleException.class, NotFoundException.class})
@RequiredArgsConstructor
public class LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionEventPublisher transactionEventPublisher;
    private final LedgerClock ledgerClock;
    private final AccountService accountService;
    private final PostingService postingService;
    private final AccountMapper accountMapper;
    private final TransactionMapper transactionMapper;

    /** Opens an account; without an overdraft limit in the request, the limit is zero. */
    public AccountDto openAccount(OpenAccountRequestDto request) {
        Account account = accountService.openAccount(UuidV7.next(), request.getCurrency(),
                accountMapper.toRequestedOverdraftLimit(request), request.getLabel(), ledgerClock.getCurrentTime());
        accountRepository.insert(account);
        return accountMapper.toAccountDto(account);
    }

    /** Records a transaction as requested through the API. */
    public TransactionDto recordTransaction(RecordTransactionRequestDto request) {
        Transaction transaction = recordTransaction(transactionMapper.toTransactionDraft(request));
        return transactionMapper.toTransactionDto(transaction);
    }

    public Transaction recordTransaction(TransactionDraft draft) {
        // 1. Lock every account the transaction touches, in id order. From here until commit, no other
        //    transaction can change these balances, so the checks below can't be invalidated.
        Map<UUID, Account> locked = accountRepository.lockAll(draft.accountIds());

        // 2. Apply the ledger's rules to the locked state. Throws, having written nothing, if refused.
        Posting posting = postingService.post(UuidV7.next(), draft, locked, ledgerClock.getCurrentTime());

        // 3. Write: the transaction and its entries, the new balances, and the transaction-recorded event.
        try {
            transactionRepository.insert(posting.transaction());
        } catch (DuplicateKeyException e) {
            if (draft.reversesId() != null) {
                // Backstop only: reverse() serialises on the original, so this means someone bypassed it.
                throw alreadyReversed(draft.reversesId());
            }
            throw e;
        }
        accountRepository.updateBalances(posting.updatedAccounts());
        transactionEventPublisher.publishTransactionRecorded(posting.transaction());
        return posting.transaction();
    }

    /** @param request optional; without a description the reversal gets a default one */
    public TransactionDto reverseTransaction(UUID transactionId, ReverseTransactionRequestDto request) {
        String description = request == null ? null : request.getDescription();
        // Lock the original first: two reversals of the same transaction queue up here, and the second
        // one then sees the first one's reversal.
        Transaction original = transactionRepository.lock(transactionId)
                .orElseThrow(() -> new NotFoundException("Transaction", transactionId));
        if (transactionRepository.findReversalOf(transactionId).isPresent()) {
            throw alreadyReversed(transactionId);
        }
        Transaction reversal = recordTransaction(postingService.createReversalDraft(original, description));
        return transactionMapper.toTransactionDto(reversal);
    }

    /**
     * Records a settled payment: debit the settlement account in its currency, credit the merchant.
     * Recorded once per payment id, however many times and under however many event ids it arrives.
     *
     * @throws LedgerRuleException if it can't be recorded (unknown account, wrong currency, bad amount),
     *                             or PAYMENT_CONFLICT if the payment was already recorded differently
     */
    public SettlementResult recordSettledPayment(String paymentId, UUID merchantAccountId, BigDecimal amount,
                                                 String currencyCode) {
        LedgerCurrency currency = LedgerCurrency.of(currencyCode, "/currency");
        Optional<Transaction> existing = transactionRepository.findByPaymentId(paymentId);
        if (existing.isPresent()) {
            return duplicate(existing.get(), merchantAccountId, amount, currency);
        }
        TransactionDraft draft = new TransactionDraft("Payment " + paymentId + " settled", List.of(
                new EntryDraft(SettlementAccounts.of(currency), Direction.DEBIT, amount),
                new EntryDraft(merchantAccountId, Direction.CREDIT, amount)), null, paymentId);
        try {
            return new SettlementResult(recordTransaction(draft), false);
        } catch (DuplicateKeyException e) {
            // The same payment, recorded concurrently by another consumer and committed first. Our INSERT
            // was the first write, so nothing of ours was written.
            Transaction recorded = transactionRepository.findByPaymentId(paymentId).orElseThrow(() -> e);
            return duplicate(recorded, merchantAccountId, amount, currency);
        }
    }

    /** @param duplicate whether the payment had already been recorded (nothing new was written) */
    public record SettlementResult(Transaction transaction, boolean duplicate) {
    }

    private static SettlementResult duplicate(Transaction recorded, UUID merchantAccountId, BigDecimal amount,
                                              LedgerCurrency currency) {
        boolean same = recorded.currency() == currency
                && recorded.entries().stream().anyMatch(e -> e.direction() == Direction.CREDIT
                && e.accountId().equals(merchantAccountId)
                && new Money(e.amount(), currency).toBigDecimal().compareTo(amount) == 0);
        if (!same) {
            throw new LedgerRuleException(null, ErrorCode.PAYMENT_CONFLICT, "Payment " + recorded.paymentId()
                    + " was already recorded as transaction " + recorded.id() + " with different details");
        }
        return new SettlementResult(recorded, true);
    }

    private static LedgerRuleException alreadyReversed(UUID transactionId) {
        return new LedgerRuleException(null, ErrorCode.ALREADY_REVERSED,
                "Transaction " + transactionId + " has already been reversed");
    }
}
