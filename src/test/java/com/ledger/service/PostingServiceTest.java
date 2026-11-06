package com.ledger.service;

import static com.ledger.model.Direction.CREDIT;
import static com.ledger.model.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledger.entity.Account;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The spec's worked examples, played through the posting rules. */
class PostingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    private final AccountService accountService = new AccountService();
    private final PostingService postingService = new PostingService(accountService);
    private final Map<UUID, Account> accounts = new HashMap<>();
    private Account settlementEur;
    private Account settlementJpy;
    private Account merchant42;
    private Account payoutsEur;
    private Account feesEur;
    private Account merchant7;
    private Account payoutsJpy;

    @BeforeEach
    void openAccounts() {
        settlementEur = add(new Account(UUID.randomUUID(), LedgerCurrency.EUR, null, 0, "Settlement EUR", NOW));
        settlementJpy = add(new Account(UUID.randomUUID(), LedgerCurrency.JPY, null, 0, "Settlement JPY", NOW));
        merchant42 = add(open("EUR", "Merchant 42"));
        payoutsEur = add(open("EUR", "Payouts EUR"));
        feesEur = add(open("EUR", "Fees EUR"));
        merchant7 = add(open("JPY", "Merchant 7"));
        payoutsJpy = add(open("JPY", "Payouts JPY"));
    }

    @Test
    void examples_1_to_3_settle_pay_out_and_reverse() {
        // 1. A payment of 125.50 EUR settles for Merchant 42
        Posting settled = postAndApply(TransactionDraft.of("Payment settled", List.of(
                entry(settlementEur, DEBIT, "125.50"),
                entry(merchant42, CREDIT, "125.50"))));
        assertBalance(settlementEur, "-125.50");
        assertBalance(merchant42, "125.50");
        assertThat(settled.transaction().currency()).isEqualTo(LedgerCurrency.EUR);

        // 2. Merchant 42 is paid out 98.50 EUR, with a fee of 1.50
        Posting payout = postAndApply(TransactionDraft.of("Payout", List.of(
                entry(merchant42, DEBIT, "100.00"),
                entry(payoutsEur, CREDIT, "98.50"),
                entry(feesEur, CREDIT, "1.50"))));
        assertBalance(merchant42, "25.50");
        assertBalance(payoutsEur, "98.50");
        assertBalance(feesEur, "1.50");
        assertThat(payout.transaction().entries()).extracting(e -> new Money(e.balanceAfter(), LedgerCurrency.EUR).toPlainString())
                .containsExactly("25.50", "98.50", "1.50");
        assertEurSumsToZero();

        // 3. The payout is reversed: every entry flipped, linked to the original
        TransactionDraft reversal = postingService.createReversalDraft(payout.transaction(), null);
        assertThat(reversal.reversesId()).isEqualTo(payout.transaction().id());
        assertThat(reversal.entries()).extracting(EntryDraft::direction).containsExactly(CREDIT, DEBIT, DEBIT);
        Posting reversed = postAndApply(reversal);
        assertBalance(merchant42, "125.50");
        assertBalance(payoutsEur, "0.00");
        assertBalance(feesEur, "0.00");
        assertThat(reversed.transaction().reversesId()).isEqualTo(payout.transaction().id());
        assertEurSumsToZero();

        // A reversal can't be reversed
        assertRefused(() -> postingService.createReversalDraft(reversed.transaction(), null), ErrorCode.REVERSAL_NOT_REVERSIBLE, null);
    }

    @Test
    void example_4_jpy_has_no_decimal_places() {
        postAndApply(TransactionDraft.of("Payment settled", List.of(
                entry(settlementJpy, DEBIT, "15000"),
                entry(merchant7, CREDIT, "15000"))));
        assertBalance(settlementJpy, "-15000");
        assertBalance(merchant7, "15000");
    }

    @Nested
    class Example5Refusals {

        @BeforeEach
        void balancesAfterExample2() {
            postAndApply(TransactionDraft.of("settle", List.of(
                    entry(settlementEur, DEBIT, "125.50"), entry(merchant42, CREDIT, "125.50"))));
            postAndApply(TransactionDraft.of("payout", List.of(
                    entry(merchant42, DEBIT, "100.00"), entry(payoutsEur, CREDIT, "98.50"),
                    entry(feesEur, CREDIT, "1.50"))));
        }

        @Test
        void debits_and_credits_dont_balance() {
            assertRefused(() -> post(merchant42, DEBIT, "100.00", payoutsEur, CREDIT, "99.00"),
                    ErrorCode.UNBALANCED, "/entries");
        }

        @Test
        void past_the_overdraft_limit() {
            var e = assertRefused(() -> post(merchant42, DEBIT, "50.00", payoutsEur, CREDIT, "50.00"),
                    ErrorCode.OVERDRAFT_LIMIT_EXCEEDED, "/entries/0");
            assertThat(e.getViolations().get(0).detail()).contains("-24.50").contains("overdraft limit of 0.00");
        }

        @Test
        void jpy_has_no_decimal_places() {
            assertRefused(() -> post(merchant7, DEBIT, "100.5", payoutsJpy, CREDIT, "100.5"),
                    ErrorCode.AMOUNT_PRECISION_EXCEEDED, "/entries/0/amount");
        }

        @Test
        void accounts_in_different_currencies() {
            assertRefused(() -> post(merchant42, DEBIT, "10.00", merchant7, CREDIT, "10"),
                    ErrorCode.CURRENCY_MISMATCH, "/entries/1/accountId");
        }

        @Test
        void at_least_two_entries() {
            assertRefused(() -> postingService.post(UUID.randomUUID(),
                            TransactionDraft.of("one", List.of(entry(merchant42, CREDIT, "10.00"))), accounts, NOW),
                    ErrorCode.TOO_FEW_ENTRIES, "/entries");
        }

        @Test
        void at_least_two_different_accounts() {
            assertRefused(() -> post(merchant42, DEBIT, "10.00", merchant42, CREDIT, "10.00"),
                    ErrorCode.SAME_ACCOUNT, "/entries/1/accountId");
        }

        @Test
        void the_same_unknown_account_twice_is_unknown_not_same_account() {
            UUID unknown = UUID.randomUUID();
            var e = assertRefused(() -> postingService.post(UUID.randomUUID(), TransactionDraft.of("x", List.of(
                            new EntryDraft(unknown, DEBIT, new BigDecimal("10.00")),
                            new EntryDraft(unknown, CREDIT, new BigDecimal("10.00")))), accounts, NOW),
                    ErrorCode.UNKNOWN_ACCOUNT, "/entries/0/accountId");
            assertThat(e.getViolations()).extracting(Violation::code).containsOnly(ErrorCode.UNKNOWN_ACCOUNT);
        }

        @Test
        void unknown_account() {
            UUID unknown = UUID.randomUUID();
            assertRefused(() -> postingService.post(UUID.randomUUID(), TransactionDraft.of("x", List.of(
                            entry(merchant42, DEBIT, "10.00"),
                            new EntryDraft(unknown, CREDIT, new BigDecimal("10.00")))), accounts, NOW),
                    ErrorCode.UNKNOWN_ACCOUNT, "/entries/1/accountId");
        }

        @Test
        void not_an_iso_currency_code() {
            assertRefused(() -> open("EURO", "x"), ErrorCode.INVALID_CURRENCY, "/currency");
            assertRefused(() -> open("ABC", "x"), ErrorCode.INVALID_CURRENCY, "/currency");
        }

        @Test
        void iso_but_unsupported_currency() {
            assertRefused(() -> open("GBP", "x"), ErrorCode.UNSUPPORTED_CURRENCY, "/currency");
        }
    }

    @Test
    void every_problem_found_is_reported_with_where_it_is() {
        var e = assertRefused(() -> postingService.post(UUID.randomUUID(), TransactionDraft.of("x", List.of(
                        entry(merchant42, DEBIT, "0"),
                        entry(merchant7, CREDIT, "1.5"),
                        new EntryDraft(UUID.randomUUID(), CREDIT, BigDecimal.ONE))), accounts, NOW),
                ErrorCode.AMOUNT_NOT_POSITIVE, "/entries/0/amount");
        assertThat(e.getViolations()).extracting(Violation::pointer, Violation::code).containsExactly(
                org.assertj.core.groups.Tuple.tuple("/entries/0/amount", ErrorCode.AMOUNT_NOT_POSITIVE),
                org.assertj.core.groups.Tuple.tuple("/entries/1/accountId", ErrorCode.CURRENCY_MISMATCH),
                org.assertj.core.groups.Tuple.tuple("/entries/1/amount", ErrorCode.AMOUNT_PRECISION_EXCEEDED),
                org.assertj.core.groups.Tuple.tuple("/entries/2/accountId", ErrorCode.UNKNOWN_ACCOUNT));
    }

    @Test
    void trailing_zeros_dont_count_as_decimal_places() {
        postAndApply(TransactionDraft.of("x", List.of(
                entry(settlementJpy, DEBIT, "100.000"), entry(merchant7, CREDIT, "100"))));
        assertBalance(merchant7, "100");
    }

    @Test
    void an_overdraft_limit_allows_going_below_zero_up_to_it() {
        Account withLimit = add(accountService.openAccount(UUID.randomUUID(), "EUR", new BigDecimal("50"), "Overdraft", NOW));
        postAndApply(TransactionDraft.of("ok", List.of(
                entry(withLimit, DEBIT, "50.00"), entry(payoutsEur, CREDIT, "50.00"))));
        assertBalance(withLimit, "-50.00");
        assertRefused(() -> post(withLimit, DEBIT, "0.01", payoutsEur, CREDIT, "0.01"),
                ErrorCode.OVERDRAFT_LIMIT_EXCEEDED, "/entries/0");
    }

    @Test
    void the_limit_applies_to_where_an_account_ends_up_within_one_transaction() {
        // Merchant 42 is at 0: debited 10 then credited 10 in the same transaction ends at 0, which is fine.
        postAndApply(TransactionDraft.of("x", List.of(
                entry(merchant42, DEBIT, "10.00"), entry(payoutsEur, CREDIT, "10.00"),
                entry(payoutsEur, DEBIT, "10.00"), entry(merchant42, CREDIT, "10.00"))));
        assertBalance(merchant42, "0.00");
    }

    @Test
    void money_is_exact() {
        Money a = Money.of("0.10", LedgerCurrency.EUR);
        Money b = Money.of("0.20", LedgerCurrency.EUR);
        assertThat(a.plus(b)).isEqualTo(Money.of("0.30", LedgerCurrency.EUR));
        assertThat(a.plus(b).toPlainString()).isEqualTo("0.30");
    }

    @Test
    void absurdly_large_amounts_are_refused_not_wrapped() {
        assertRefused(() -> post(settlementEur, DEBIT, "99999999999999999999", merchant42, CREDIT, "99999999999999999999"),
                ErrorCode.AMOUNT_OUT_OF_RANGE, "/entries/0/amount");
        // Each fits in a long of cents, but the debits together don't
        assertRefused(() -> postingService.post(UUID.randomUUID(), TransactionDraft.of("x", List.of(
                        entry(settlementEur, DEBIT, "92233720368547758.07"), entry(settlementEur, DEBIT, "0.01"),
                        entry(merchant42, CREDIT, "92233720368547758.07"), entry(payoutsEur, CREDIT, "0.01"))),
                        accounts, NOW),
                ErrorCode.AMOUNT_OUT_OF_RANGE, "/entries");
    }

    // --- helpers ---

    private Account open(String currency, String label) {
        return accountService.openAccount(UUID.randomUUID(), currency, null, label, NOW);
    }

    private Account add(Account account) {
        accounts.put(account.id(), account);
        return account;
    }

    private static EntryDraft entry(Account account, Direction direction, String amount) {
        return new EntryDraft(account.id(), direction, new BigDecimal(amount));
    }

    private Posting post(Account a, Direction da, String amountA, Account b, Direction db, String amountB) {
        return postingService.post(UUID.randomUUID(),
                TransactionDraft.of("x", List.of(entry(a, da, amountA), entry(b, db, amountB))), accounts, NOW);
    }

    private Posting postAndApply(TransactionDraft draft) {
        Posting posting = postingService.post(UUID.randomUUID(), draft, accounts, NOW);
        posting.updatedAccounts().forEach(this::add);
        return posting;
    }

    private void assertBalance(Account account, String expected) {
        assertThat(accountService.getBalance(accounts.get(account.id())).toPlainString()).isEqualTo(expected);
    }

    private void assertEurSumsToZero() {
        long sum = accounts.values().stream().filter(a -> a.currency() == LedgerCurrency.EUR)
                .mapToLong(Account::balance).sum();
        assertThat(sum).isZero();
    }

    /** @param pointer the first violation's pointer; {@code null} to not check it */
    private static LedgerRuleException assertRefused(Runnable action, ErrorCode code, String pointer) {
        LedgerRuleException thrown = null;
        try {
            action.run();
        } catch (LedgerRuleException e) {
            thrown = e;
        }
        assertThat(thrown).as("expected a refusal with " + code).isNotNull();
        assertThat(thrown.code()).isEqualTo(code);
        if (pointer != null) {
            assertThat(thrown.getViolations().get(0).pointer()).isEqualTo(pointer);
        }
        return thrown;
    }
}
