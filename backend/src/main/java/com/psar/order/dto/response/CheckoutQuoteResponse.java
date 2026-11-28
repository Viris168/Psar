package com.psar.order.dto.response;

import com.psar.order.entity.DeliveryType;
import com.psar.order.entity.PaymentMethod;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the current cart would cost at checkout. Amounts are USD, scale 2. {@code deliveryCompanyName} is null for
 * CITY. {@code rewardDiscount} stays 0 until reward vouchers (Phase 7).
 *
 * @param promotions the promotions that took something off (an item one summed over its lines; FREE_DELIVERY
 *                   with the waived fee)
 * @param coupon     null if no code was entered; otherwise whether it applies and, if not, why (a coupon that
 *                   can't be used is explained here, not an error; checkout rejects it with 422)
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
        PaymentMethod paymentMethod,
        List<AppliedPromotion> promotions,
        Coupon coupon
) {
    /**
     * {@code lineTotal} is before the discount; {@code freeQuantity} (buy X get Y) is part of {@code quantity};
     * {@code promotionName} is the item promotion used on this line, null if none.
     */
    public record Line(UUID productPublicId, String slug, String name, String unit, String imageUrl, int quantity,
                       int freeQuantity, BigDecimal unitPrice, BigDecimal discountAmount, BigDecimal lineTotal,
                       String promotionName) {
    }

    /** @param level "ITEM" or "ORDER" */
    public record AppliedPromotion(String name, String level, BigDecimal discountAmount) {
    }

    /** @param reason why it doesn't apply; null when {@code applied} */
    public record Coupon(String code, boolean applied, String reason) {
    }
}
