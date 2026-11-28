package com.psar.promotion.dto.response;

import java.math.BigDecimal;

/**
 * A promotion an order used, as snapshotted at checkout (for order detail responses).
 *
 * @param level    "ITEM" or "ORDER"
 * @param released the order was cancelled / expired / refunded and the use was given back
 */
public record OrderPromotionView(String promotionName, String couponCode, String level,
                                 BigDecimal discountAmount, boolean released) {
}
