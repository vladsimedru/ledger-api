package com.ledger.mapper;

import static com.ledger.model.Direction.CREDIT;
import static com.ledger.model.Direction.DEBIT;
import static com.ledger.model.LedgerCurrency.EUR;
import static com.ledger.model.LedgerCurrency.JPY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ledger.api.generated.model.DirectionDto;
import com.ledger.api.generated.model.EntryDto;
import com.ledger.api.generated.model.EntryRequestDto;
import com.ledger.api.generated.model.RecordTransactionRequestDto;
import com.ledger.api.generated.model.TransactionDto;
import com.ledger.entity.Entry;
import com.ledger.entity.Transaction;
import com.ledger.model.EntryDraft;
import com.ledger.model.LedgerCurrency;
import com.ledger.model.TransactionDraft;
import com.ledger.model.TransactionView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Only the generated mappers in the context: no database, no web server. */
@SpringJUnitConfig({TransactionMapperImpl.class, CommonMapperImpl.class})
class TransactionMapperTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final UUID SETTLEMENT = UUID.fromString("00000000-0000-0000-0000-000000000978");
    private static final UUID MERCHANT = UUID.fromString("01960000-0000-7000-8000-000000000042");

    @Autowired
    TransactionMapper transactionMapper;

    @Test
    void a_request_becomes_a_draft_with_exact_amounts() {
        RecordTransactionRequestDto request = new RecordTransactionRequestDto("Payment settled", List.of(
                new EntryRequestDto(SETTLEMENT, DirectionDto.DEBIT, "125.50"),
                new EntryRequestDto(MERCHANT, DirectionDto.CREDIT, "125.50")));

        TransactionDraft draft = transactionMapper.toTransactionDraft(request);

        assertThat(draft.description()).isEqualTo("Payment settled");
        assertThat(draft.entries())
                .extracting(EntryDraft::accountId, EntryDraft::direction, EntryDraft::amount)
                .containsExactly(
                        tuple(SETTLEMENT, DEBIT, new BigDecimal("125.50")),
                        tuple(MERCHANT, CREDIT, new BigDecimal("125.50")));
        assertThat(draft.reversesId()).isNull();
        assertThat(draft.paymentId()).isNull();
    }

    @Test
    void a_recorded_transaction_has_its_amounts_in_its_currency_and_no_reversal_yet() {
        Transaction transaction = new Transaction(UUID.randomUUID(), "Payment settled", EUR, NOW, null, "pay-123",
                List.of(new Entry(SETTLEMENT, DEBIT, 12550, -12550, NOW), new Entry(MERCHANT, CREDIT, 12550, 12550, NOW)));

        TransactionDto dto = transactionMapper.toTransactionDto(transaction);

        assertThat(dto.getId()).isEqualTo(transaction.id());
        assertThat(dto.getDescription()).isEqualTo("Payment settled");
        assertThat(dto.getCurrency()).isEqualTo("EUR");
        assertThat(dto.getRecordedAt()).isEqualTo(OffsetDateTime.parse("2026-11-04T10:00:00Z"));
        assertThat(dto.getPaymentId()).isEqualTo("pay-123");
        assertThat(dto.getReversesTransactionId()).isNull();
        assertThat(dto.getReversedByTransactionId()).isNull();
        assertThat(dto.getEntries())
                .extracting(EntryDto::getAccountId, EntryDto::getDirection, EntryDto::getAmount)
                .containsExactly(
                        tuple(SETTLEMENT, DirectionDto.DEBIT, "125.50"),
                        tuple(MERCHANT, DirectionDto.CREDIT, "125.50"));
    }

    @Test
    void a_transaction_read_back_names_the_reversal_that_reversed_it() {
        Transaction transaction = transaction(EUR, null);
        UUID reversalId = UUID.randomUUID();

        TransactionDto dto = transactionMapper.toTransactionDto(new TransactionView(transaction, reversalId));

        assertThat(dto.getReversedByTransactionId()).isEqualTo(reversalId);
    }

    @Test
    void a_reversal_names_the_transaction_it_reverses() {
        UUID originalId = UUID.randomUUID();

        assertThat(transactionMapper.toTransactionDto(transaction(EUR, originalId)).getReversesTransactionId())
                .isEqualTo(originalId);
    }

    @Test
    void jpy_amounts_have_no_decimal_places() {
        assertThat(transactionMapper.toTransactionDto(transaction(JPY, null)).getEntries())
                .extracting(EntryDto::getAmount).containsOnly("15000");
    }

    private static Transaction transaction(LedgerCurrency currency, UUID reversesId) {
        return new Transaction(UUID.randomUUID(), "Payout", currency, NOW, reversesId, null, List.of(
                new Entry(MERCHANT, DEBIT, 15000, 0, NOW), new Entry(SETTLEMENT, CREDIT, 15000, 15000, NOW)));
    }
}
