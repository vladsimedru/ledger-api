package com.ledger.service;

import static com.ledger.model.LedgerCurrency.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ledger.api.generated.model.AccountDto;
import com.ledger.api.generated.model.OpenAccountRequestDto;
import com.ledger.api.generated.model.ReverseTransactionRequestDto;
import com.ledger.api.generated.model.TransactionDto;
import com.ledger.entity.Account;
import com.ledger.entity.Entry;
import com.ledger.entity.Transaction;
import com.ledger.exception.ErrorCode;
import com.ledger.exception.LedgerRuleException;
import com.ledger.exception.NotFoundException;
import com.ledger.mapper.AccountMapper;
import com.ledger.mapper.TransactionMapper;
import com.ledger.messaging.TransactionEventPublisher;
import com.ledger.model.Direction;
import com.ledger.model.EntryDraft;
import com.ledger.model.SettlementAccounts;
import com.ledger.model.SettlementResult;
import com.ledger.model.TransactionDraft;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.TransactionRepository;
import com.ledger.support.LedgerClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * The write use cases, with the database, clock, event publisher and mappers mocked and the ledger's rules
 * ({@link AccountService}, {@link PostingService}) real: what is locked, what is written, and that a
 * refusal writes nothing.
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final UUID SETTLEMENT = SettlementAccounts.of(EUR);
    private static final UUID MERCHANT = UUID.fromString("01960000-0000-7000-8000-000000000042");
    private static final UUID PAYOUTS = UUID.fromString("01960000-0000-7000-8000-000000000043");

    @Mock
    AccountRepository accountRepository;
    @Mock
    TransactionRepository transactionRepository;
    @Mock
    TransactionEventPublisher transactionEventPublisher;
    @Mock
    LedgerClock ledgerClock;
    @Mock
    AccountMapper accountMapper;
    @Mock
    TransactionMapper transactionMapper;

    LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        AccountService accountService = new AccountService();
        ledgerService = new LedgerService(accountRepository, transactionRepository, transactionEventPublisher,
                ledgerClock, accountService, new PostingService(accountService), accountMapper, transactionMapper);
    }

    @Nested
    class OpenAccount {

        @Test
        void inserts_the_account_opened_at_the_ledger_clock_and_returns_it_mapped() {
            OpenAccountRequestDto request = new OpenAccountRequestDto().currency("EUR").label("Merchant 42");
            AccountDto dto = new AccountDto();
            when(ledgerClock.getCurrentTime()).thenReturn(NOW);
            when(accountMapper.toRequestedOverdraftLimit(request)).thenReturn(new BigDecimal("100.00"));
            when(accountMapper.toAccountDto(any())).thenReturn(dto);

            assertThat(ledgerService.openAccount(request)).isSameAs(dto);

            ArgumentCaptor<Account> inserted = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).insert(inserted.capture());
            assertThat(inserted.getValue().currency()).isEqualTo(EUR);
            assertThat(inserted.getValue().overdraftLimit()).isEqualTo(10000);
            assertThat(inserted.getValue().balance()).isZero();
            assertThat(inserted.getValue().label()).isEqualTo("Merchant 42");
            assertThat(inserted.getValue().createdAt()).isEqualTo(NOW);
        }

        @Test
        void a_refused_account_is_not_inserted() {
            OpenAccountRequestDto request = new OpenAccountRequestDto().currency("GBP");
            when(ledgerClock.getCurrentTime()).thenReturn(NOW);

            assertRefused(() -> ledgerService.openAccount(request), ErrorCode.UNSUPPORTED_CURRENCY);
            verify(accountRepository, never()).insert(any());
        }
    }

    @Nested
    class RecordTransaction {

        @Test
        void locks_the_accounts_then_writes_the_transaction_the_balances_and_the_event() {
            givenLocked(account(MERCHANT, 5000), account(PAYOUTS, 0));
            when(ledgerClock.getCurrentTime()).thenReturn(NOW);

            Transaction recorded = ledgerService.recordTransaction(transfer(MERCHANT, PAYOUTS, "30.00"));

            assertThat(recorded.currency()).isEqualTo(EUR);
            assertThat(recorded.recordedAt()).isEqualTo(NOW);
            assertThat(recorded.entries()).extracting(Entry::accountId, Entry::direction, Entry::amount, Entry::balanceAfter)
                    .containsExactly(
                            tuple(MERCHANT, Direction.DEBIT, 3000L, 2000L),
                            tuple(PAYOUTS, Direction.CREDIT, 3000L, 3000L));
            verify(accountRepository).lockAll(Set.of(MERCHANT, PAYOUTS));
            verify(transactionRepository).insert(recorded);
            verify(accountRepository).updateBalances(List.of(account(MERCHANT, 2000), account(PAYOUTS, 3000)));
            verify(transactionEventPublisher).publishTransactionRecorded(recorded);
        }

        @Test
        void a_refused_transaction_writes_nothing() {
            givenLocked(account(MERCHANT, 1000), account(PAYOUTS, 0));
            when(ledgerClock.getCurrentTime()).thenReturn(NOW);

            assertRefused(() -> ledgerService.recordTransaction(transfer(MERCHANT, PAYOUTS, "50.00")),
                    ErrorCode.OVERDRAFT_LIMIT_EXCEEDED);
            assertNothingWritten();
        }

        @Test
        void an_unknown_account_is_refused_and_writes_nothing() {
            givenLocked(account(MERCHANT, 1000));
            when(ledgerClock.getCurrentTime()).thenReturn(NOW);

            assertRefused(() -> ledgerService.recordTransaction(transfer(MERCHANT, PAYOUTS, "1.00")),
                    ErrorCode.UNKNOWN_ACCOUNT);
            assertNothingWritten();
        }
    }

    @Nested
    class ReverseTransaction {

        private final UUID originalId = UUID.fromString("01960000-0000-7000-8000-0000000000aa");

        @Test
        void records_the_original_with_every_entry_flipped() {
            when(transactionRepository.lock(originalId)).thenReturn(Optional.of(original()));
            when(transactionRepository.findReversalOf(originalId)).thenReturn(Optional.empty());
            givenLocked(account(MERCHANT, 2000), account(PAYOUTS, 3000));
            when(ledgerClock.getCurrentTime()).thenReturn(NOW);
            TransactionDto dto = new TransactionDto();
            when(transactionMapper.toTransactionDto(any(Transaction.class))).thenReturn(dto);

            assertThat(ledgerService.reverseTransaction(originalId,
                    new ReverseTransactionRequestDto().description("Refund"))).isSameAs(dto);

            ArgumentCaptor<Transaction> inserted = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).insert(inserted.capture());
            Transaction reversal = inserted.getValue();
            assertThat(reversal.reversesId()).isEqualTo(originalId);
            assertThat(reversal.description()).isEqualTo("Refund");
            assertThat(reversal.entries()).extracting(Entry::accountId, Entry::direction)
                    .containsExactly(
                            tuple(MERCHANT, Direction.CREDIT),
                            tuple(PAYOUTS, Direction.DEBIT));
            verify(transactionEventPublisher).publishTransactionRecorded(reversal);
        }

        @Test
        void an_unknown_transaction_is_not_found() {
            when(transactionRepository.lock(originalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ledgerService.reverseTransaction(originalId, null))
                    .isInstanceOf(NotFoundException.class);
            assertNothingWritten();
        }

        @Test
        void a_transaction_already_reversed_is_refused() {
            when(transactionRepository.lock(originalId)).thenReturn(Optional.of(original()));
            when(transactionRepository.findReversalOf(originalId)).thenReturn(Optional.of(UUID.randomUUID()));

            assertRefused(() -> ledgerService.reverseTransaction(originalId, null), ErrorCode.ALREADY_REVERSED);
            assertNothingWritten();
        }

        @Test
        void a_reversal_is_not_reversible() {
            Transaction reversal = new Transaction(originalId, "Reversal", EUR, NOW, UUID.randomUUID(), null,
                    original().entries());
            when(transactionRepository.lock(originalId)).thenReturn(Optional.of(reversal));
            when(transactionRepository.findReversalOf(originalId)).thenReturn(Optional.empty());

            assertRefused(() -> ledgerService.reverseTransaction(originalId, null), ErrorCode.REVERSAL_NOT_REVERSIBLE);
            assertNothingWritten();
        }

        @Test
        void a_concurrent_reversal_that_got_past_the_lock_is_refused_by_the_unique_constraint() {
            when(transactionRepository.lock(originalId)).thenReturn(Optional.of(original()));
            when(transactionRepository.findReversalOf(originalId)).thenReturn(Optional.empty());
            givenLocked(account(MERCHANT, 2000), account(PAYOUTS, 3000));
            when(ledgerClock.getCurrentTime()).thenReturn(NOW);
            doThrow(new DuplicateKeyException("uk_reverses_id"))
                    .when(transactionRepository).insert(any());

            assertRefused(() -> ledgerService.reverseTransaction(originalId, null), ErrorCode.ALREADY_REVERSED);
            verify(accountRepository, never()).updateBalances(any());
            verifyNoInteractions(transactionEventPublisher);
        }

        private Transaction original() {
            return new Transaction(originalId, "Payout", EUR, NOW, null, null, List.of(
                    new Entry(MERCHANT, Direction.DEBIT, 3000, 2000, NOW),
                    new Entry(PAYOUTS, Direction.CREDIT, 3000, 3000, NOW)));
        }
    }

    @Nested
    class RecordSettledPayment {

        @Test
        void debits_the_settlement_account_and_credits_the_merchant() {
            when(transactionRepository.findByPaymentId("pay-1")).thenReturn(Optional.empty());
            givenLocked(settlement(), account(MERCHANT, 0));
            when(ledgerClock.getCurrentTime()).thenReturn(NOW);

            SettlementResult result = ledgerService.recordSettledPayment("pay-1", MERCHANT, new BigDecimal("125.50"), "EUR");

            assertThat(result.duplicate()).isFalse();
            assertThat(result.transaction().paymentId()).isEqualTo("pay-1");
            assertThat(result.transaction().entries()).extracting(Entry::accountId, Entry::direction, Entry::amount)
                    .containsExactly(
                            tuple(SETTLEMENT, Direction.DEBIT, 12550L),
                            tuple(MERCHANT, Direction.CREDIT, 12550L));
            verify(transactionRepository).insert(result.transaction());
        }

        @Test
        void the_same_payment_again_is_a_duplicate_and_writes_nothing() {
            when(transactionRepository.findByPaymentId("pay-1")).thenReturn(Optional.of(settled("pay-1", 12550)));

            SettlementResult result = ledgerService.recordSettledPayment("pay-1", MERCHANT, new BigDecimal("125.5"), "EUR");

            assertThat(result.duplicate()).isTrue();
            assertNothingWritten();
        }

        @Test
        void the_same_payment_with_other_details_is_a_conflict() {
            when(transactionRepository.findByPaymentId("pay-1")).thenReturn(Optional.of(settled("pay-1", 12550)));

            assertRefused(() -> ledgerService.recordSettledPayment("pay-1", MERCHANT, new BigDecimal("99.00"), "EUR"),
                    ErrorCode.PAYMENT_CONFLICT);
            assertNothingWritten();
        }

        @Test
        void looks_for_the_payment_only_once_its_accounts_are_locked() {
            // So a copy handled concurrently elsewhere waits on the lock, then finds the committed payment
            // (proven against MySQL in MessagingIT).
            when(transactionRepository.findByPaymentId("pay-1")).thenReturn(Optional.of(settled("pay-1", 12550)));

            ledgerService.recordSettledPayment("pay-1", MERCHANT, new BigDecimal("125.50"), "EUR");

            InOrder order = inOrder(accountRepository, transactionRepository);
            order.verify(accountRepository).lockAll(Set.of(SETTLEMENT, MERCHANT));
            order.verify(transactionRepository).findByPaymentId("pay-1");
            assertNothingWritten();
        }

        @Test
        void an_unsupported_currency_is_refused_before_anything_is_read() {
            assertRefused(() -> ledgerService.recordSettledPayment("pay-1", MERCHANT, BigDecimal.ONE, "GBP"),
                    ErrorCode.UNSUPPORTED_CURRENCY);
            verifyNoInteractions(transactionRepository, accountRepository, transactionEventPublisher);
        }

        private Transaction settled(String paymentId, long amount) {
            return new Transaction(UUID.randomUUID(), "Payment " + paymentId + " settled", EUR, NOW, null, paymentId,
                    List.of(new Entry(SETTLEMENT, Direction.DEBIT, amount, -amount, NOW),
                            new Entry(MERCHANT, Direction.CREDIT, amount, amount, NOW)));
        }
    }

    // --- helpers ---

    private void givenLocked(Account... accounts) {
        Map<UUID, Account> locked = new HashMap<>();
        for (Account a : accounts) {
            locked.put(a.id(), a);
        }
        when(accountRepository.lockAll(any())).thenReturn(locked);
    }

    private static Account account(UUID id, long balance) {
        return new Account(id, EUR, 0L, balance, null, NOW);
    }

    private static Account settlement() {
        return new Account(SETTLEMENT, EUR, null, 0, "Settlement EUR", NOW);
    }

    private static TransactionDraft transfer(UUID from, UUID to, String amount) {
        return TransactionDraft.of("transfer", List.of(
                new EntryDraft(from, Direction.DEBIT, new BigDecimal(amount)),
                new EntryDraft(to, Direction.CREDIT, new BigDecimal(amount))));
    }

    private void assertNothingWritten() {
        verify(transactionRepository, never()).insert(any());
        verify(accountRepository, never()).updateBalances(any());
        verify(accountRepository, never()).insert(any());
        verifyNoInteractions(transactionEventPublisher);
    }

    private static void assertRefused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LedgerRuleException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
