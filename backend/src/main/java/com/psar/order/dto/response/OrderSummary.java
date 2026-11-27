package com.psar.order.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An order for other modules (notification listeners, later payments and points). {@code userId} is internal:
 * never put this record in an API response. Enum values are their names as strings.
 */
public record OrderSummary(
        Long id,
        UUID publicId,
        String orderNo,
        Long userId,
        String status,
        String paymentMethod,
        String deliveryType,
        BigDecimal totalAmount
) {
}
