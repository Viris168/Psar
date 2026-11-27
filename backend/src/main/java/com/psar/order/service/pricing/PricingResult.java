package com.psar.order.service.pricing;

import java.math.BigDecimal;
import java.util.List;

/** Every amount scale 2, HALF_UP. {@code total} is never below zero. */
public record PricingResult(
        List<Line> lines,
        BigDecimal subtotal,
        BigDecimal productDiscount,
        BigDecimal orderDiscount,
        BigDecimal rewardDiscount,
        BigDecimal deliveryFee,
        BigDecimal total
) {
    public PricingResult {
        lines = List.copyOf(lines);
    }

    /** {@code freeQuantity} and {@code discountAmount} stay 0 until Phase 6 (buy X get Y, item promotions). */
    public record Line(Long productId, int quantity, int freeQuantity, BigDecimal unitPrice,
                       BigDecimal discountAmount, BigDecimal lineTotal) {
    }
}
