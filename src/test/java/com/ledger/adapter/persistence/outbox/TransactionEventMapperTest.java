package com.ledger.adapter.persistence.outbox;

import static com.ledger.domain.model.Direction.CREDIT;
import static com.ledger.domain.model.Direction.DEBIT;
import static com.ledger.domain.model.LedgerCurrency.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ledger.adapter.mapper.CommonMapperImpl;
import com.ledger.domain.model.Entry;
import com.ledger.domain.model.Transaction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Only the generated mappers in the context: no database, no Kafka. */
@SpringJUnitConfig({TransactionEventMapperImpl.class, CommonMapperImpl.class})
class TransactionEventMapperTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final UUID SETTLEMENT = UUID.fromString("00000000-0000-0000-0000-000000000978");
    private static final UUID MERCHANT = UUID.fromString("01960000-0000-7000-8000-000000000042");

    @Autowired
    TransactionEventMapper transactionEventMapper;

    @Test
    void the_event_has_ids_and_amounts_as_strings_with_each_entrys_balance_after() {
        UUID originalId = UUID.randomUUID();
        Transaction reversal = new Transaction(UUID.randomUUID(), "Reversal", EUR, NOW, originalId, null, List.of(
                new Entry(MERCHANT, DEBIT, 12550, -2450, NOW), new Entry(SETTLEMENT, CREDIT, 12550, 0, NOW)));

        TransactionRecordedEvent.Body body = transactionEventMapper.toEventBody(reversal);

        assertThat(body.id()).isEqualTo(reversal.id().toString());
        assertThat(body.description()).isEqualTo("Reversal");
        assertThat(body.currency()).isEqualTo("EUR");
        assertThat(body.recordedAt()).isEqualTo(NOW);
        assertThat(body.reversesTransactionId()).isEqualTo(originalId.toString());
        assertThat(body.paymentId()).isNull();
        assertThat(body.entries())
                .extracting(TransactionRecordedEvent.Line::accountId, TransactionRecordedEvent.Line::direction,
                        TransactionRecordedEvent.Line::amount, TransactionRecordedEvent.Line::balanceAfter)
                .containsExactly(
                        tuple(MERCHANT.toString(), "DEBIT", "125.50", "-24.50"),
                        tuple(SETTLEMENT.toString(), "CREDIT", "125.50", "0.00"));
    }

    @Test
    void a_settled_payment_carries_its_payment_id() {
        Transaction settled = new Transaction(UUID.randomUUID(), "Payment settled", EUR, NOW, null, "pay-123", List.of(
                new Entry(SETTLEMENT, DEBIT, 100, -100, NOW), new Entry(MERCHANT, CREDIT, 100, 100, NOW)));

        TransactionRecordedEvent.Body body = transactionEventMapper.toEventBody(settled);

        assertThat(body.paymentId()).isEqualTo("pay-123");
        assertThat(body.reversesTransactionId()).isNull();
    }
}
