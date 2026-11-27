package com.psar.payment.gateway;

import java.math.BigDecimal;
import java.time.Instant;

/** What a gateway needs to create a charge. {@code reference} is the order number (KHQR bill number). */
public record ChargeRequest(String reference, BigDecimal amount, String currency, Instant expiresAt) {
}
