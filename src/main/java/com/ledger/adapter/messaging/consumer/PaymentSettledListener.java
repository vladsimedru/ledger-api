package com.ledger.adapter.messaging.consumer;

import com.ledger.adapter.messaging.exception.InvalidPaymentMessageException;
import com.ledger.adapter.web.filter.RequestIdFilter;
import com.ledger.application.service.LedgerService.SettlementResult;
import com.ledger.application.service.LedgerService;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records payment-settled messages from the payment system. The offset is committed only after the
 * transaction is (or the message has been dead-lettered), so a crash means redelivery, never loss;
 * redelivery is harmless because a payment is recorded once per payment id.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class PaymentSettledListener {

    private final LedgerService ledgerService;
    private final JsonMapper jsonMapper;

    /**
     * The message the payment system sends. {@code amount} is a decimal string (a JSON number is accepted
     * too, and read from its text, never through a double).
     */
    record PaymentSettled(String eventId, String paymentId, String merchantAccountId, String amount,
                          String currency) {
    }

    @KafkaListener(topics = "${ledger.topics.payments-settled}", concurrency = "3")
    void onPaymentSettled(ConsumerRecord<String, String> record) {
        PaymentSettled message = parseMessage(record.value());
        MDC.put("paymentId", message.paymentId());
        // The event id doubles as the request id, so the announcement this causes can be traced back to it.
        MDC.put(RequestIdFilter.MDC_KEY, message.eventId() != null ? message.eventId() : "payment-" + message.paymentId());
        try {
            SettlementResult result = ledgerService.recordSettledPayment(message.paymentId(),
                    UUID.fromString(message.merchantAccountId()), new BigDecimal(message.amount()), message.currency());
            if (result.duplicate()) {
                log.info("Payment {} already recorded as transaction {}; event {} ignored", message.paymentId(),
                        result.transaction().id(), message.eventId());
            } else {
                log.info("Payment {} recorded as transaction {}", message.paymentId(), result.transaction().id());
            }
        } catch (RuntimeException e) {
            log.warn("Payment {} (event {}) not recorded: {}", message.paymentId(), message.eventId(), e.getMessage());
            throw e;
        } finally {
            MDC.remove("paymentId");
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

    private PaymentSettled parseMessage(String value) {
        PaymentSettled message;
        try {
            message = jsonMapper.readValue(value, PaymentSettled.class);
        } catch (JacksonException e) {
            throw new InvalidPaymentMessageException("Not a payment-settled message: " + e.getOriginalMessage(), e);
        } catch (RuntimeException e) {
            throw new InvalidPaymentMessageException("Not a payment-settled message", e);
        }
        if (message == null || isBlank(message.paymentId()) || isBlank(message.merchantAccountId())
                || isBlank(message.amount()) || isBlank(message.currency())) {
            throw new InvalidPaymentMessageException(
                    "paymentId, merchantAccountId, amount and currency are all required: " + value, null);
        }
        try {
            UUID.fromString(message.merchantAccountId());
            new BigDecimal(message.amount());
        } catch (IllegalArgumentException e) {
            throw new InvalidPaymentMessageException("Malformed merchantAccountId or amount: " + value, e);
        }
        return message;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
