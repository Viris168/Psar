package com.psar.order.dto.response;

import com.psar.order.entity.DeliveryType;
import com.psar.order.entity.PaymentMethod;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the current cart would cost at checkout. Amounts are USD, scale 2. {@code deliveryCompanyName} is null for
 * CITY. Discounts stay 0 until promotions (Phase 6) and reward vouchers (Phase 7).
 */
public record CheckoutQuoteResponse(
        List<Line> lines,
        BigDecimal subtotal,
        BigDecimal productDiscount,
        BigDecimal orderDiscount,
        BigDecimal rewardDiscount,
        BigDecimal deliveryFee,
        BigDecimal total,
        DeliveryType deliveryType,
        String deliveryCompanyName,
        PaymentMethod paymentMethod
) {
    public record Line(UUID productPublicId, String slug, String name, String unit, String imageUrl, int quantity,
                       BigDecimal unitPrice, BigDecimal discountAmount, BigDecimal lineTotal) {
    }
}
