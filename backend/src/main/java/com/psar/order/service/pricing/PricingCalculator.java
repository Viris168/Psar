package com.psar.order.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Pure pricing logic: no Spring, no repositories, no I/O.
 * CheckoutService (and the checkout quote) load the data, pass it in, and use the result.
 * <p>
 * Order of calculation (architecture.md §5.5, CLAUDE.md "Pricing order"):
 * <ol>
 *   <li>line total = unit price × quantity, per line; subtotal = sum of the line totals</li>
 *   <li>{@code productDiscount}: the best item promotion per line (Phase 6 — buy X get Y fills
 *       {@code freeQuantity}, a percentage / fixed promotion fills the line's {@code discountAmount})</li>
 *   <li>{@code orderDiscount}: one coupon on what is left (Phase 6)</li>
 *   <li>{@code rewardDiscount}: one reward voucher from points (Phase 7)</li>
 *   <li>+ delivery fee (from DeliveryQuoteService; Phase 8 makes it 0 for members)</li>
 *   <li>total = subtotal − discounts + delivery fee, never below 0</li>
 * </ol>
 * Phase 4: steps 2–4 are 0. Every amount is scale 2, {@link RoundingMode#HALF_UP}; a line total is rounded once,
 * after unit price × quantity (prices from the database are already scale 2, so this only matters for odd input).
 */
public final class PricingCalculator {

    public static final int MONEY_SCALE = 2;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    /** Rounds a money amount to 2 decimals, HALF_UP. */
    public static BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Prices an order: line totals, subtotal, discounts (0 until Phase 6), delivery fee and total. */
    public PricingResult price(PricingInput input) {
        Objects.requireNonNull(input, "input");
        List<PricingResult.Line> lines = input.lines().stream().map(PricingCalculator::priceLine).toList();

        BigDecimal subtotal = money(lines.stream().map(PricingResult.Line::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        // Phase 6: productDiscount = sum of line discounts; orderDiscount = the coupon. Phase 7: rewardDiscount.
        BigDecimal productDiscount = money(lines.stream().map(PricingResult.Line::discountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal orderDiscount = ZERO;
        BigDecimal rewardDiscount = ZERO;
        BigDecimal fee = money(nonNegative(input.deliveryFee(), "deliveryFee"));

        BigDecimal discounts = productDiscount.add(orderDiscount).add(rewardDiscount);
        return new PricingResult(lines, subtotal, productDiscount, orderDiscount, rewardDiscount, fee,
                total(subtotal, discounts, fee));
    }

    /** total = subtotal - discounts + deliveryFee, never below zero. */
    public BigDecimal total(BigDecimal subtotal, BigDecimal discounts, BigDecimal deliveryFee) {
        BigDecimal total = subtotal.subtract(discounts).add(deliveryFee);
        return money(total.max(BigDecimal.ZERO));
    }

    private static PricingResult.Line priceLine(PricingInput.Line line) {
        if (line.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be positive for product " + line.productId());
        }
        BigDecimal unitPrice = nonNegative(line.unitPrice(), "unitPrice");
        BigDecimal lineTotal = money(unitPrice.multiply(BigDecimal.valueOf(line.quantity())));
        return new PricingResult.Line(line.productId(), line.quantity(), 0, money(unitPrice), ZERO, lineTotal);
    }

    private static BigDecimal nonNegative(BigDecimal amount, String name) {
        Objects.requireNonNull(amount, name);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return amount;
    }
}
