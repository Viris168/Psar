package com.psar.order.service.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What {@link PricingCalculator#price} needs. Prices come from the database, never from the client.
 * {@code offers} are the promotions that can apply (automatic ones and at most one coupon), already filtered by
 * dates, limits and targets; pricing picks which ones are used. Phase 7 adds the reward voucher, Phase 8 the
 * member flag.
 */
public record PricingInput(List<Line> lines, BigDecimal deliveryFee, List<Offer> offers) {

    public PricingInput {
        lines = List.copyOf(lines);
        offers = List.copyOf(offers);
        if (offers.stream().filter(Offer::coupon).count() > 1) {
            throw new IllegalArgumentException("at most one coupon per order");
        }
    }

    /** No promotions. */
    public PricingInput(List<Line> lines, BigDecimal deliveryFee) {
        this(lines, deliveryFee, List.of());
    }

    public record Line(Long productId, int quantity, BigDecimal unitPrice) {
    }

    public enum OfferType {
        PERCENTAGE,
        FIXED_AMOUNT,
        BUY_X_GET_Y,
        FREE_DELIVERY
    }

    /** ITEM: applies per matching line (best one per line). ORDER: the one order-level slot. */
    public enum OfferLevel {
        ITEM,
        ORDER
    }

    /**
     * One promotion that may apply.
     *
     * @param value          PERCENTAGE: percent (e.g. 10.00); FIXED_AMOUNT: dollars off (per unit for ITEM,
     *                       once for ORDER); null for BUY_X_GET_Y and FREE_DELIVERY
     * @param maxDiscount    PERCENTAGE cap (per line for ITEM, per order for ORDER); null = no cap
     * @param buyQuantity    BUY_X_GET_Y: X
     * @param getQuantity    BUY_X_GET_Y: Y (every X+Y units of one line, Y are free)
     * @param minOrderAmount null = none. ORDER: subtotal after item discounts must be ≥ this; ITEM: the
     *                       subtotal before discounts must be ≥ this
     * @param productIds     ITEM: exactly the line product ids it applies to; empty for ORDER
     * @param coupon         the customer entered its code (wins the order slot; see PricingCalculator)
     * @param endsAt         tie-break: the one that ends first wins, then the lowest id
     */
    public record Offer(
            Long id,
            String name,
            OfferType type,
            OfferLevel level,
            BigDecimal value,
            BigDecimal maxDiscount,
            Integer buyQuantity,
            Integer getQuantity,
            BigDecimal minOrderAmount,
            Set<Long> productIds,
            boolean coupon,
            Instant endsAt
    ) {
        public Offer {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(endsAt, "endsAt");
            productIds = Set.copyOf(productIds);
        }
    }
}
