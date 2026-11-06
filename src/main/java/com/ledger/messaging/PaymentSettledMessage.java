package com.ledger.messaging;

/**
 * The payment-settled message the payment system sends. {@code amount} is a decimal string (a JSON number
 * is accepted too, and read from its text, never through a double).
 */
public record PaymentSettledMessage(String eventId, String paymentId, String merchantAccountId, String amount,
                                    String currency) {
}
