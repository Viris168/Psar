package com.psar.payment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment for other modules (orders, admin screens). Enum values as names. {@code qrString} is the KHQR the
 * customer scans; the provider reference (md5) and the bank's transaction reference are never in here.
 */
public record PaymentView(
        UUID publicId,
        String method,
        String status,
        BigDecimal amount,
        String currency,
        String qrString,
        String redirectUrl,
        Instant expiresAt,
        Instant paidAt,
        boolean refundRequired
) {
}
