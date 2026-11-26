package com.psar.order.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure pricing logic: no Spring, no repositories, no I/O.
 * CheckoutService loads the data, passes it in, and saves the result.
 *
 * Order of calculation (see architecture.md section 5.5):
 *   subtotal -> best item promotion -> one coupon -> one reward voucher
 *   -> delivery fee -> total (never below 0)
 *
 * Phase 4 implements subtotal + delivery fee; Phase 6 adds promotions; Phase 7 adds vouchers.
 */
public final class PricingCalculator {

    public static final int MONEY_SCALE = 2;

    /** Rounds a money amount to 2 decimals, HALF_UP. */
    public static BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** total = subtotal - discounts + deliveryFee, never below zero. */
    public BigDecimal total(BigDecimal subtotal, BigDecimal discounts, BigDecimal deliveryFee) {
        BigDecimal total = subtotal.subtract(discounts).add(deliveryFee);
        return money(total.max(BigDecimal.ZERO));
    }
}
