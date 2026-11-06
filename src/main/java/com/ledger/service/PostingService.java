package com.ledger.service;

import com.ledger.entity.Account;
import com.ledger.entity.Entry;
import com.ledger.entity.Transaction;
import com.ledger.exception.ErrorCode;
import com.ledger.exception.LedgerRuleException;
import com.ledger.exception.Violation;
import com.ledger.model.Direction;
import com.ledger.model.EntryDraft;
import com.ledger.model.LedgerCurrency;
import com.ledger.model.Money;
import com.ledger.model.Posting;
import com.ledger.model.TransactionDraft;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The ledger's rules for recording a transaction. Given a draft and the current state of its accounts,
 * it returns either a {@link Posting} (the transaction and the accounts' new balances) or a
 * {@link LedgerRuleException} saying why not. Nothing is written here; the caller persists the result,
 * holding locks on the accounts so that their state can't change in between.
 */
@Service
@RequiredArgsConstructor
public class PostingService {

    private final AccountService accountService;

    /**
     * @param accounts the current state of the draft's accounts; an id missing from it is an unknown account
     * @throws LedgerRuleException with every violation that can be found, each pointing at the part
     *                             of the request at fault
     */
    public Posting post(UUID transactionId, TransactionDraft draft, Map<UUID, Account> accounts, Instant recordedAt) {
        List<EntryDraft> drafts = draft.entries();
        LedgerCurrency currency = checkEntries(drafts, accounts);
        checkBalanced(drafts, currency);

        // Apply in request order, so each entry knows the balance right after it.
        Map<UUID, Account> updated = new LinkedHashMap<>();
        Map<UUID, Integer> firstEntryOf = new LinkedHashMap<>();
        List<Entry> entries = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            EntryDraft d = drafts.get(i);
            Money amount = Money.of(d.amount(), currency);
            Account before = updated.getOrDefault(d.accountId(), accounts.get(d.accountId()));
            Account after = accountService.applyEntry(before, d.direction(), amount);
            updated.put(d.accountId(), after);
            firstEntryOf.putIfAbsent(d.accountId(), i);
            entries.add(new Entry(d.accountId(), d.direction(), amount.minorUnits(), after.balance(), recordedAt));
        }

        // A transaction is atomic, so the limit applies to where each account ends up.
        List<Violation> violations = new ArrayList<>();
        for (Account a : updated.values()) {
            if (!accountService.isWithinOverdraftLimit(a)) {
                violations.add(new Violation("/entries/" + firstEntryOf.get(a.id()), ErrorCode.OVERDRAFT_LIMIT_EXCEEDED,
                        accountService.getDisplayName(a) + " would go to "
                                + accountService.getBalance(a).toPlainString() + ", past its overdraft limit of "
                                + accountService.getOverdraftLimit(a).toPlainString()));
            }
        }
        throwIfAny(violations);

        Transaction transaction = new Transaction(transactionId, draft.description(), currency, recordedAt,
                draft.reversesId(), draft.paymentId(), entries);
        return new Posting(transaction, List.copyOf(updated.values()));
    }

    /**
     * The draft of the transaction that reverses {@code original}: every entry flipped, linked to the
     * original. Whether it was already reversed is the caller's to check; the database also guarantees
     * it with a unique constraint.
     *
     * @param description {@code null} for the default description
     * @throws LedgerRuleException REVERSAL_NOT_REVERSIBLE if {@code original} is itself a reversal
     */
    public TransactionDraft createReversalDraft(Transaction original, String description) {
        if (original.reversesId() != null) {
            throw new LedgerRuleException(null, ErrorCode.REVERSAL_NOT_REVERSIBLE,
                    "Transaction " + original.id() + " is a reversal and can't be reversed");
        }
        List<EntryDraft> flipped = original.entries().stream()
                .map(e -> new EntryDraft(e.accountId(), e.direction().flip(),
                        new Money(e.amount(), original.currency()).toBigDecimal()))
                .toList();
        return new TransactionDraft(description != null ? description : "Reversal of " + original.id(), flipped,
                original.id(), null);
    }

    /** Structure, accounts, currencies and amounts. Returns the transaction's currency. */
    private LedgerCurrency checkEntries(List<EntryDraft> drafts, Map<UUID, Account> accounts) {
        List<Violation> violations = new ArrayList<>();
        if (drafts.size() < 2) {
            violations.add(new Violation("/entries", ErrorCode.TOO_FEW_ENTRIES,
                    "A transaction needs at least two entries, this one has " + drafts.size()));
        } else if (drafts.stream().map(EntryDraft::accountId).distinct().count() == 1) {
            // Debit and credit the same account: nothing would change, so it's a mistake, not a transaction.
            violations.add(new Violation("/entries/1/accountId", ErrorCode.SAME_ACCOUNT,
                    "Every entry posts to account " + drafts.get(0).accountId()
                            + "; a transaction must move money between at least two accounts"));
        }
        LedgerCurrency currency = null;
        for (int i = 0; i < drafts.size(); i++) {
            EntryDraft d = drafts.get(i);
            String at = "/entries/" + i;
            if (d.amount().signum() <= 0) {
                violations.add(new Violation(at + "/amount", ErrorCode.AMOUNT_NOT_POSITIVE,
                        "Amounts must be positive, got " + d.amount().toPlainString()));
            }
            Account account = accounts.get(d.accountId());
            if (account == null) {
                violations.add(new Violation(at + "/accountId", ErrorCode.UNKNOWN_ACCOUNT,
                        "Account " + d.accountId() + " doesn't exist"));
                continue;
            }
            if (currency == null) {
                currency = account.currency();
            } else if (account.currency() != currency) {
                violations.add(new Violation(at + "/accountId", ErrorCode.CURRENCY_MISMATCH,
                        "The accounts are in different currencies: " + accountService.getDisplayName(account)
                                + " is in " + account.currency() + ", the transaction is in " + currency));
            }
            if (!account.currency().fits(d.amount())) {
                violations.add(new Violation(at + "/amount", ErrorCode.AMOUNT_PRECISION_EXCEEDED,
                        d.amount().toPlainString() + ": " + account.currency().precisionRule()));
            } else if (exceedsRange(d.amount(), account.currency())) {
                violations.add(new Violation(at + "/amount", ErrorCode.AMOUNT_OUT_OF_RANGE,
                        d.amount().toPlainString() + " is too large"));
            }
        }
        throwIfAny(violations);
        return currency;
    }

    private static void checkBalanced(List<EntryDraft> drafts, LedgerCurrency currency) {
        Money debits = Money.zero(currency);
        Money credits = Money.zero(currency);
        try {
            for (EntryDraft d : drafts) {
                Money amount = Money.of(d.amount(), currency);
                if (d.direction() == Direction.DEBIT) {
                    debits = debits.plus(amount);
                } else {
                    credits = credits.plus(amount);
                }
            }
        } catch (ArithmeticException e) {
            throw new LedgerRuleException("/entries", ErrorCode.AMOUNT_OUT_OF_RANGE, "The amounts add up to too much");
        }
        if (!debits.equals(credits)) {
            throw new LedgerRuleException("/entries", ErrorCode.UNBALANCED,
                    "Debits and credits don't balance: debits total " + debits.toPlainString()
                            + ", credits total " + credits.toPlainString());
        }
    }

    private static boolean exceedsRange(BigDecimal amount, LedgerCurrency currency) {
        try {
            Money.of(amount, currency);
            return false;
        } catch (ArithmeticException e) {
            return true;
        }
    }

    private static void throwIfAny(List<Violation> violations) {
        if (!violations.isEmpty()) {
            throw new LedgerRuleException(violations);
        }
    }
}
