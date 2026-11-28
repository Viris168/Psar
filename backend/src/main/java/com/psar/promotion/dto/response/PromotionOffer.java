package com.psar.promotion.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * A promotion that can apply to this cart right now, as pricing needs it (the order module may not use the
 * promotion entities). Enum values travel as their names.
 *
 * @param type        PromotionType name: PERCENTAGE, FIXED_AMOUNT, BUY_X_GET_Y, FREE_DELIVERY
 * @param level       "ITEM" (apply_to ALL / PRODUCTS / CATEGORIES) or "ORDER"
 * @param productIds  ITEM only: exactly the cart's product ids it applies to (ALL → every line; never empty
 *                    for an ITEM offer); empty for ORDER
 * @param couponCode  null for an automatic promotion
 */
public record PromotionOffer(
        Long id,
        String name,
        String type,
        String level,
        BigDecimal value,
        BigDecimal maxDiscount,
        Integer buyQuantity,
        Integer getQuantity,
        BigDecimal minOrderAmount,
        Set<Long> productIds,
        String couponCode,
        Instant endsAt
) {
    public PromotionOffer {
        productIds = Set.copyOf(productIds);
    }

    public boolean isCoupon() {
        return couponCode != null;
    }
}
