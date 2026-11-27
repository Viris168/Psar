package com.psar.payment.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** A payment the gateway says was made: its transaction reference, what was paid and the raw answer. */
public record GatewayPayment(String transactionRef, BigDecimal amount, String currency, Instant paidAt,
                             Map<String, Object> raw) {
}
