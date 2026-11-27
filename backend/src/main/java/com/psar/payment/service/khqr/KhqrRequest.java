package com.psar.payment.service.khqr;

import java.math.BigDecimal;
import java.time.Instant;

/** {@code billNumber} = the order number; {@code amount} scale 2. */
public record KhqrRequest(String billNumber, BigDecimal amount, String currency, Instant expiresAt) {
}
