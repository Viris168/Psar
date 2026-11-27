package com.psar.payment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A row of the admin payments list. Enum values as names. */
public record AdminPaymentListItemResponse(
        UUID publicId,
        UUID orderPublicId,
        String orderNo,
        String method,
        String status,
        String provider,
        BigDecimal amount,
        String currency,
        boolean refundRequired,
        Instant paidAt,
        Instant refundedAt,
        Instant createdAt
) {
}
