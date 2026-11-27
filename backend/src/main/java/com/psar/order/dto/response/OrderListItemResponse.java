package com.psar.order.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A row of "my orders". */
public record OrderListItemResponse(
        UUID publicId,
        String orderNo,
        String status,
        String paymentMethod,
        String deliveryType,
        BigDecimal totalAmount,
        Instant expiresAt,
        Instant createdAt
) {
}
