package com.psar.promotion.dto.request;

import java.math.BigDecimal;

/**
 * A promotion the priced order actually used, for {@code PromotionUsageService.claim}.
 *
 * @param level          "ITEM" or "ORDER" ({@code DiscountLevel} names)
 * @param discountAmount what it took off this order (all lines together for ITEM; the waived fee for
 *                       FREE_DELIVERY), scale 2
 */
public record UsedPromotion(Long promotionId, String level, BigDecimal discountAmount) {
}
