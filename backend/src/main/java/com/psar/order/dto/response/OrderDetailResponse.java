package com.psar.order.dto.response;

import com.psar.promotion.dto.response.OrderPromotionView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The customer's order page: items, money, the promotions it used (as snapshotted at checkout), delivery snapshot,
 * tracking link and timeline (oldest first).
 */
public record OrderDetailResponse(
        UUID publicId,
        String orderNo,
        String status,
        String paymentMethod,
        String paymentStatus,
        Instant expiresAt,
        String note,
        Instant createdAt,
        List<OrderItemResponse> items,
        OrderMoneyResponse money,
        List<OrderPromotionView> promotions,
        OrderDeliveryResponse delivery,
        List<OrderTimelineEntryResponse> timeline
) {
}
