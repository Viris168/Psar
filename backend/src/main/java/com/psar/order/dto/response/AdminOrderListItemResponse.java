package com.psar.order.dto.response;

import com.psar.user.dto.response.UserSummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A row of the staff order list. */
public record AdminOrderListItemResponse(
        UUID publicId,
        String orderNo,
        String status,
        String paymentMethod,
        String deliveryType,
        String receiverName,
        String receiverPhone,
        BigDecimal totalAmount,
        UserSummary assignedDriver,
        String trackingNo,
        Instant createdAt
) {
}
