package com.ledger.mapper;

import com.ledger.entity.Entry;
import com.ledger.entity.Transaction;
import com.ledger.messaging.EntryPayload;
import com.ledger.messaging.TransactionPayload;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static com.ledger.model.Direction.CREDIT;
import static com.ledger.model.Direction.DEBIT;
import static com.ledger.model.LedgerCurrency.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;


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

        TransactionPayload payload = transactionEventMapper.toTransactionPayload(reversal);

        assertThat(payload.id()).isEqualTo(reversal.id().toString());
        assertThat(payload.description()).isEqualTo("Reversal");
        assertThat(payload.currency()).isEqualTo("EUR");
        assertThat(payload.recordedAt()).isEqualTo(NOW);
        assertThat(payload.reversesTransactionId()).isEqualTo(originalId.toString());
        assertThat(payload.paymentId()).isNull();
        assertThat(payload.entries())
                .extracting(EntryPayload::accountId, EntryPayload::direction,
                        EntryPayload::amount, EntryPayload::balanceAfter)
                .containsExactly(
                        tuple(MERCHANT.toString(), "DEBIT", "125.50", "-24.50"),
                        tuple(SETTLEMENT.toString(), "CREDIT", "125.50", "0.00"));
    }

    @Test
    void a_settled_payment_carries_its_payment_id() {
        Transaction settled = new Transaction(UUID.randomUUID(), "Payment settled", EUR, NOW, null, "pay-123", List.of(
                new Entry(SETTLEMENT, DEBIT, 100, -100, NOW), new Entry(MERCHANT, CREDIT, 100, 100, NOW)));

        TransactionPayload payload = transactionEventMapper.toTransactionPayload(settled);

        assertThat(payload.paymentId()).isEqualTo("pay-123");
        assertThat(payload.reversesTransactionId()).isNull();
    }
}
