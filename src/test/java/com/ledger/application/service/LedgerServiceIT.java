package com.ledger.application.service;

import static com.ledger.domain.model.Direction.CREDIT;
import static com.ledger.domain.model.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledger.TestcontainersConfiguration;
import com.ledger.application.exception.NotFoundException;
import com.ledger.domain.exception.ErrorCode;
import com.ledger.domain.exception.LedgerRuleException;
import com.ledger.domain.model.Account;
import com.ledger.domain.model.Direction;
import com.ledger.domain.model.EntryDraft;
import com.ledger.domain.model.Transaction;
import com.ledger.domain.model.TransactionDraft;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The write path against a real MySQL: atomicity, immutability, concurrency and integrity. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerServiceIT {

    private static final UUID SETTLEMENT_EUR = UUID.fromString("00000000-0000-0000-0000-000000000978");

    @Autowired
    LedgerService ledgerService;
    @Autowired
    LedgerQueryService ledgerQueryService;
    @Autowired
    JdbcClient jdbcClient;

    @AfterEach
    void balancesOfEachCurrencyAddUpToZero() {
        List<Map<String, Object>> sums = jdbcClient.sql("SELECT currency, SUM(balance) AS total FROM accounts GROUP BY currency")
                .query().listOfRows();
        assertThat(sums).allSatisfy(row -> assertThat(((Number) row.get("total")).longValue())
                .as("sum of %s balances", row.get("currency")).isZero());
        // ...and every balance equals the sum of its account's entries
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM accounts a
                WHERE a.balance <> COALESCE((SELECT SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END)
                                             FROM entries e WHERE e.account_id = a.id), 0)""")
                .query(Long.class).single()).isZero();
    }

    @Test
    void records_reads_back_and_reverses_once() {
        Account merchant = open("Merchant 42");
        Account payouts = open("Payouts EUR");
        Account fees = open("Fees EUR");
        fund(merchant, "125.50");

        Transaction payout = ledgerService.recordTransaction(draft(entry(merchant, DEBIT, "100.00"),
                entry(payouts, CREDIT, "98.50"), entry(fees, CREDIT, "1.50")));

        assertThat(ledgerQueryService.getTransaction(payout.id()).transaction()).isEqualTo(payout);
        assertBalance(merchant, "25.50");

        Transaction reversal = ledgerService.reverseTransaction(payout.id(), null);
        assertThat(reversal.reversesId()).isEqualTo(payout.id());
        assertThat(ledgerQueryService.getTransaction(payout.id()).reversedBy()).isEqualTo(reversal.id());
        assertThat(ledgerQueryService.getTransaction(payout.id()).transaction()).as("the original is unchanged").isEqualTo(payout);
        assertBalance(merchant, "125.50");
        assertBalance(payouts, "0.00");
        assertBalance(fees, "0.00");

        assertRefused(() -> ledgerService.reverseTransaction(payout.id(), null), ErrorCode.ALREADY_REVERSED);
        assertRefused(() -> ledgerService.reverseTransaction(reversal.id(), null), ErrorCode.REVERSAL_NOT_REVERSIBLE);
        assertThatThrownBy(() -> ledgerService.reverseTransaction(UUID.randomUUID(), null)).isInstanceOf(NotFoundException.class);

        assertThat(outboxEventsFor(payout.id())).isEqualTo(1);
        assertThat(outboxEventsFor(reversal.id())).isEqualTo(1);
    }

    @Test
    void a_refused_transaction_leaves_nothing_behind() {
        Account merchant = open("Merchant");
        Account payouts = open("Payouts");
        fund(merchant, "25.50");
        long transactions = count("transactions");
        long entries = count("entries");
        long outbox = count("outbox_events");

        assertRefused(() -> ledgerService.recordTransaction(draft(entry(merchant, DEBIT, "50.00"), entry(payouts, CREDIT, "50.00"))),
                ErrorCode.OVERDRAFT_LIMIT_EXCEEDED);
        assertRefused(() -> ledgerService.recordTransaction(draft(entry(merchant, DEBIT, "10.00"), entry(payouts, CREDIT, "9.00"))),
                ErrorCode.UNBALANCED);
        assertRefused(() -> ledgerService.recordTransaction(draft(entry(merchant, DEBIT, "10.00"),
                new EntryDraft(UUID.randomUUID(), CREDIT, new BigDecimal("10.00")))), ErrorCode.UNKNOWN_ACCOUNT);

        assertThat(count("transactions")).isEqualTo(transactions);
        assertThat(count("entries")).isEqualTo(entries);
        assertThat(count("outbox_events")).isEqualTo(outbox);
        assertBalance(merchant, "25.50");
        assertBalance(payouts, "0.00");
    }

    @Test
    void transactions_and_entries_cannot_be_changed_even_by_hand() {
        Account merchant = open("Merchant");
        Transaction t = fund(merchant, "10.00");
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE transactions SET description = 'x' WHERE id = ?")
                .param(t.id().toString()).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM entries WHERE transaction_id = ?")
                .param(t.id().toString()).update()).hasMessageContaining("immutable");
    }

    @Test
    void money_moving_both_ways_at_once_never_deadlocks_or_loses_an_update() throws Exception {
        Account a = open("A");
        Account b = open("B");
        fund(a, "1000.00");
        fund(b, "1000.00");

        int each = 100;
        List<Callable<Transaction>> work = new ArrayList<>();
        for (int i = 0; i < each; i++) {
            work.add(() -> ledgerService.recordTransaction(draft(entry(a, DEBIT, "1.00"), entry(b, CREDIT, "1.00"))));
            work.add(() -> ledgerService.recordTransaction(draft(entry(b, DEBIT, "2.00"), entry(a, CREDIT, "2.00"))));
        }
        Results results = runConcurrently(work);

        assertThat(results.failures).as("no deadlocks, no errors").isEmpty();
        assertBalance(a, "1100.00"); // 1000 - 100 * 1 + 100 * 2
        assertBalance(b, "900.00");
    }

    @Test
    void concurrent_debits_never_break_the_overdraft_limit() throws Exception {
        Account merchant = open("Merchant");
        Account payouts = open("Payouts");
        fund(merchant, "100.00");

        List<Callable<Transaction>> work = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            work.add(() -> ledgerService.recordTransaction(draft(entry(merchant, DEBIT, "10.00"), entry(payouts, CREDIT, "10.00"))));
        }
        Results results = runConcurrently(work);

        assertThat(results.successes).isEqualTo(10);
        assertThat(results.failures).hasSize(40).allSatisfy(e -> assertThat(e)
                .isInstanceOfSatisfying(LedgerRuleException.class,
                        r -> assertThat(r.code()).isEqualTo(ErrorCode.OVERDRAFT_LIMIT_EXCEEDED)));
        assertBalance(merchant, "0.00");
        assertBalance(payouts, "100.00");
    }

    @Test
    void concurrent_reversals_of_one_transaction_record_exactly_one() throws Exception {
        Account merchant = open("Merchant");
        Transaction funding = fund(merchant, "10.00");

        List<Callable<Transaction>> work = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            work.add(() -> ledgerService.reverseTransaction(funding.id(), null));
        }
        Results results = runConcurrently(work);

        assertThat(results.successes).isEqualTo(1);
        assertThat(results.failures).hasSize(19).allSatisfy(e -> assertThat(e)
                .isInstanceOfSatisfying(LedgerRuleException.class,
                        r -> assertThat(r.code()).isEqualTo(ErrorCode.ALREADY_REVERSED)));
        assertBalance(merchant, "0.00");
    }

    @Test
    void entries_page_newest_first_and_balance_as_at_a_past_instant() {
        Account merchant = open("Merchant");
        fund(merchant, "1.00");
        Instant afterFirst = ledgerQueryService.getBalance(merchant.id(), null).asOf();
        fund(merchant, "2.00");
        fund(merchant, "3.00");

        LedgerQueryService.EntryPage first = ledgerQueryService.listEntries(merchant.id(), null, 2);
        assertThat(first.items()).extracting(e -> e.balanceAfter().toPlainString()).containsExactly("6.00", "3.00");
        LedgerQueryService.EntryPage second = ledgerQueryService.listEntries(merchant.id(), first.nextCursor(), 2);
        assertThat(second.items()).extracting(e -> e.balanceAfter().toPlainString()).containsExactly("1.00");
        assertThat(second.nextCursor()).isNull();

        assertThat(ledgerQueryService.getBalance(merchant.id(), afterFirst).balance().toPlainString()).isEqualTo("1.00");
        assertThat(ledgerQueryService.getBalance(merchant.id(), Instant.parse("2000-01-01T00:00:00Z")).balance().toPlainString())
                .isEqualTo("0.00");
    }

    // --- helpers ---

    private Account open(String label) {
        return ledgerService.openAccount("EUR", null, label);
    }

    private Transaction fund(Account account, String amount) {
        return ledgerService.recordTransaction(draft(new EntryDraft(SETTLEMENT_EUR, DEBIT, new BigDecimal(amount)),
                entry(account, CREDIT, amount)));
    }

    private static TransactionDraft draft(EntryDraft... entries) {
        return TransactionDraft.of("test", List.of(entries));
    }

    private static EntryDraft entry(Account account, Direction direction, String amount) {
        return new EntryDraft(account.id(), direction, new BigDecimal(amount));
    }

    private void assertBalance(Account account, String expected) {
        assertThat(ledgerQueryService.getBalance(account.id(), null).balance().toPlainString()).isEqualTo(expected);
    }

    private static void assertRefused(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LedgerRuleException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private long outboxEventsFor(UUID transactionId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox_events WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.transaction.id')) = ?")
                .param(transactionId.toString()).query(Long.class).single();
    }

    private record Results(int successes, List<Throwable> failures) {
    }

    /** Runs all the work at once from 16 threads, released together. */
    private static Results runConcurrently(List<Callable<Transaction>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (Callable<Transaction> task : work) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    task.call();
                    successes.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return new Results(successes.get(), failures);
    }
}
