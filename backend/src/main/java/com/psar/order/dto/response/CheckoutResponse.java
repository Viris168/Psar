package com.psar.order.dto.response;

import com.psar.order.entity.DeliveryType;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The order checkout just placed. {@code expiresAt} is set for KHQR orders (pay within 15 minutes), null for COD.
 */
public record CheckoutResponse(
        UUID publicId,
        String orderNo,
        OrderStatus status,
        PaymentMethod paymentMethod,
        DeliveryType deliveryType,
        String deliveryCompanyName,
        List<Line> items,
        BigDecimal subtotal,
        BigDecimal productDiscount,
        BigDecimal orderDiscount,
        BigDecimal rewardDiscount,
        BigDecimal deliveryFee,
        BigDecimal totalAmount,
        Instant expiresAt,
        Instant createdAt
) {
    public CheckoutResponse {
        items = List.copyOf(items);
    }

    /** A snapshot line: name and price as they were at checkout. */
    public record Line(UUID productPublicId, String productName, String sku, int quantity, int freeQuantity,
                       BigDecimal unitPrice, BigDecimal discountAmount, BigDecimal lineTotal) {
    }
}
