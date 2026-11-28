package com.psar.order.dto.response;

import com.psar.promotion.dto.response.OrderPromotionView;
import com.psar.user.dto.response.UserSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The staff order page: the customer's view plus the customer, the assigned driver and who changed what. */
public record AdminOrderDetailResponse(
        UUID publicId,
        String orderNo,
        String status,
        String paymentMethod,
        String paymentStatus,
        Instant expiresAt,
        String note,
        Instant createdAt,
        UserSummary customer,
        UserSummary assignedDriver,
        Instant assignedAt,
        List<OrderItemResponse> items,
        OrderMoneyResponse money,
        List<OrderPromotionView> promotions,
        OrderDeliveryResponse delivery,
        List<AdminOrderTimelineEntryResponse> timeline
) {
}
