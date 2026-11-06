package com.ledger.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledger.TestTopics;
import com.ledger.TestcontainersConfiguration;
import com.ledger.api.generated.model.AccountDto;
import com.ledger.api.generated.model.OpenAccountRequestDto;
import com.ledger.entity.Transaction;
import com.ledger.model.Direction;
import com.ledger.model.EntryDraft;
import com.ledger.model.LedgerCurrency;
import com.ledger.model.SettlementAccounts;
import com.ledger.model.SettlementResult;
import com.ledger.model.TransactionDraft;
import com.ledger.repository.TransactionRepository;
import com.ledger.service.LedgerQueryService;
import com.ledger.service.LedgerService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Payment-settled messages in, announcements out, against real MySQL and Kafka. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MessagingIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    LedgerService ledgerService;
    @Autowired
    LedgerQueryService ledgerQueryService;
    @Autowired
    TransactionRepository transactionRepository;
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    JdbcClient jdbcClient;
    @Autowired
    JsonMapper jsonMapper;

    private AccountDto openAccount(String currency, String label) {
        return ledgerService.openAccount(new OpenAccountRequestDto().currency(currency).label(label));
    }

    // --- consuming payment-settled ---

    @Test
    void a_settled_payment_debits_settlement_and_credits_the_merchant() {
        AccountDto merchant = openAccount("JPY", "Merchant 7");
        String paymentId = "pay-" + UUID.randomUUID();

        send(paymentId, settled(UUID.randomUUID().toString(), paymentId, merchant.getId(), "15000", "JPY"));

        await().atMost(WAIT).until(() -> balance(merchant).equals("15000"));
        Transaction t = transactionOf(paymentId);
        assertThat(t.entries()).extracting(e -> e.accountId(), e -> e.direction()).containsExactly(
                org.assertj.core.groups.Tuple.tuple(SettlementAccounts.of(LedgerCurrency.JPY), Direction.DEBIT),
                org.assertj.core.groups.Tuple.tuple(merchant.getId(), Direction.CREDIT));
    }

    @Test
    void the_same_payment_is_recorded_once_however_often_and_under_whatever_event_id_it_arrives() {
        AccountDto merchant = openAccount("EUR", "Merchant 42");
        String paymentId = "pay-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        send(paymentId, settled(eventId, paymentId, merchant.getId(), "125.50", "EUR"));
        send(paymentId, settled(eventId, paymentId, merchant.getId(), "125.50", "EUR"));            // redelivered
        send(paymentId, settled(UUID.randomUUID().toString(), paymentId, merchant.getId(), "125.5", "EUR")); // re-sent, new event id
        String marker = "pay-" + UUID.randomUUID();
        send(paymentId, settled(UUID.randomUUID().toString(), marker, merchant.getId(), "0.01", "EUR"));

        // Same key, same partition: once the marker is in, every copy before it has been handled.
        await().atMost(WAIT).until(() -> paymentCount(marker) == 1);
        assertThat(paymentCount(paymentId)).isEqualTo(1);
        assertThat(balance(merchant)).isEqualTo("125.51");
    }

    @Test
    void the_same_payment_handled_by_several_consumers_at_once_is_recorded_once() throws Exception {
        // Kafka hands one partition to one consumer, but a payment re-sent under another key can land on
        // another partition, so other consumers (on other instances) may handle copies at the same moment.
        AccountDto merchant = openAccount("EUR", "Merchant");
        String paymentId = "pay-" + UUID.randomUUID();
        int copies = 8;

        ExecutorService pool = Executors.newFixedThreadPool(copies);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SettlementResult>> results = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return ledgerService.recordSettledPayment(paymentId, merchant.getId(), new BigDecimal("40.00"), "EUR");
            }));
        }
        start.countDown();
        List<SettlementResult> recorded = new ArrayList<>();
        for (Future<SettlementResult> f : results) {
            recorded.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(recorded).extracting(r -> r.transaction().id()).as("every copy gets the same transaction").containsOnly(
                recorded.get(0).transaction().id());
        assertThat(recorded).filteredOn(r -> !r.duplicate()).hasSize(1);
        assertThat(paymentCount(paymentId)).isEqualTo(1);
        assertThat(balance(merchant)).isEqualTo("40.00");
    }

    @Test
    void messages_that_cant_be_recorded_go_to_the_dead_letter_topic_and_dont_block_the_rest() {
        AccountDto eurMerchant = openAccount("EUR", "EUR merchant");
        AccountDto usdMerchant = openAccount("USD", "USD merchant");
        String key = "poison-" + UUID.randomUUID();
        String conflicting = "pay-" + UUID.randomUUID();

        send(key, "{this is not json");
        send(key, settled("e1", "pay-" + UUID.randomUUID(), UUID.randomUUID(), "10.00", "EUR"));   // unknown account
        send(key, settled("e2", "pay-" + UUID.randomUUID(), usdMerchant.getId(), "10.00", "EUR"));    // wrong currency
        send(key, settled("e3", "pay-" + UUID.randomUUID(), eurMerchant.getId(), "10.005", "EUR"));   // too precise
        send(key, settled("e4", "pay-" + UUID.randomUUID(), eurMerchant.getId(), "10.00", "GBP"));    // unsupported currency
        send(key, settled("e5", conflicting, eurMerchant.getId(), "1.00", "EUR"));
        send(key, settled("e6", conflicting, eurMerchant.getId(), "2.00", "EUR"));                   // same payment, other amount
        String good = "pay-" + UUID.randomUUID();
        send(key, settled("e7", good, eurMerchant.getId(), "5.00", "EUR"));

        await().atMost(WAIT).until(() -> paymentCount(good) == 1);
        assertThat(balance(eurMerchant)).isEqualTo("6.00");

        List<ConsumerRecord<String, String>> dead = TestTopics.readUntil("payments.settled.DLT",
                r -> key.equals(r.key()), found -> found.size() >= 6, WAIT);
        assertThat(dead).hasSize(6);
        assertThat(dead).allSatisfy(r -> assertThat(TestTopics.header(r, "kafka_dlt-exception-message")).isNotBlank());
        assertThat(dead).extracting(r -> TestTopics.header(r, "kafka_dlt-exception-message")).anySatisfy(
                m -> assertThat(m).contains("different details"));
    }

    // --- announcing recorded transactions ---

    @Test
    void every_recorded_transaction_is_announced_with_its_details_in_order() {
        AccountDto a = openAccount("USD", "A");
        AccountDto b = openAccount("USD", "B");
        MDC.put("requestId", "req-" + UUID.randomUUID());
        List<UUID> recorded = new ArrayList<>();
        try {
            recorded.add(ledgerService.recordTransaction(transfer(SettlementAccounts.of(LedgerCurrency.USD), a.getId(), "10.00")).id());
            for (int i = 0; i < 5; i++) {
                recorded.add(ledgerService.recordTransaction(transfer(a.getId(), b.getId(), "1.00")).id());
            }
            recorded.add(ledgerService.reverseTransaction(recorded.get(1), null).getId());
        } finally {
            MDC.remove("requestId");
        }

        List<ConsumerRecord<String, String>> announced = TestTopics.readUntil("ledger.transactions",
                r -> recorded.contains(transactionId(r)),
                found -> found.stream().map(this::transactionId).distinct().count() == recorded.size(), WAIT);

        assertThat(announced.stream().map(this::transactionId).distinct().toList())
                .as("every transaction, in the order recorded").containsExactlyElementsOf(recorded);
        ConsumerRecord<String, String> reversal = announced.get(announced.size() - 1);
        JsonNode body = read(reversal).get("transaction");
        assertThat(body.get("reversesTransactionId").asString()).isEqualTo(recorded.get(1).toString());
        assertThat(body.get("entries")).hasSize(2);
        assertThat(reversal.key()).isEqualTo("USD");
        assertThat(TestTopics.header(reversal, "eventType")).isEqualTo("ledger.transaction.recorded.v1");
        assertThat(TestTopics.header(reversal, "requestId")).startsWith("req-");
    }

    @Test
    void a_settled_payment_is_announced_with_its_payment_id_traceable_to_the_event() {
        AccountDto merchant = openAccount("EUR", "Merchant");
        String paymentId = "pay-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        send(paymentId, settled(eventId, paymentId, merchant.getId(), "3.00", "EUR"));

        List<ConsumerRecord<String, String>> announced = TestTopics.readUntil("ledger.transactions",
                r -> paymentId.equals(read(r).at("/transaction/paymentId").asString()), found -> !found.isEmpty(), WAIT);

        assertThat(announced).isNotEmpty();
        assertThat(TestTopics.header(announced.get(0), "requestId")).isEqualTo(eventId);
    }

    @Test
    void an_announcement_waiting_in_the_outbox_survives_and_is_published() {
        // A transaction committed by an instance that died before relaying is just an unpublished outbox
        // row: whichever instance relays next publishes it.
        AccountDto merchant = openAccount("EUR", "Merchant");
        Transaction t = ledgerService.recordTransaction(transfer(SettlementAccounts.of(LedgerCurrency.EUR), merchant.getId(), "1.00"));
        await().atMost(WAIT).until(() -> published(t.id()));
    }

    // --- helpers ---

    private void send(String key, String value) {
        kafkaTemplate.send("payments.settled", key, value).join();
    }

    private static String settled(String eventId, String paymentId, UUID merchant, String amount, String currency) {
        return """
                {"eventId":"%s","paymentId":"%s","merchantAccountId":"%s","amount":"%s","currency":"%s"}"""
                .formatted(eventId, paymentId, merchant, amount, currency);
    }

    private static TransactionDraft transfer(UUID from, UUID to, String amount) {
        return TransactionDraft.of("transfer", List.of(
                new EntryDraft(from, Direction.DEBIT, new BigDecimal(amount)),
                new EntryDraft(to, Direction.CREDIT, new BigDecimal(amount))));
    }

    private String balance(AccountDto account) {
        return ledgerQueryService.getBalance(account.getId(), null).getBalance();
    }

    private long paymentCount(String paymentId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM transactions WHERE payment_id = ?").param(paymentId).query(Long.class).single();
    }

    private Transaction transactionOf(String paymentId) {
        String id = jdbcClient.sql("SELECT id FROM transactions WHERE payment_id = ?").param(paymentId).query(String.class).single();
        return transactionRepository.find(UUID.fromString(id)).orElseThrow();
    }

    private boolean published(UUID transactionId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM outbox_events
                        WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.transaction.id')) = ? AND published_at IS NOT NULL""")
                .param(transactionId.toString()).query(Long.class).single() == 1;
    }

    private JsonNode read(ConsumerRecord<String, String> r) {
        return jsonMapper.readTree(r.value());
    }

    private UUID transactionId(ConsumerRecord<String, String> r) {
        return UUID.fromString(read(r).at("/transaction/id").asString());
    }
}
