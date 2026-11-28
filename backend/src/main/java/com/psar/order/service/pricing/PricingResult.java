package com.psar.order.service.pricing;

import java.math.BigDecimal;
import java.util.List;

/**
 * Every amount scale 2, HALF_UP. {@code total} is never below zero.
 *
 * @param orderDiscount  the order-level PERCENTAGE / FIXED_AMOUNT promotion; FREE_DELIVERY instead makes
 *                       {@code deliveryFee} 0 (and is listed in {@code appliedOffers} with the waived fee)
 * @param appliedOffers  every offer that took something off, one entry per offer (an ITEM offer's lines summed)
 * @param couponStatus   what happened to the coupon offer, if there was one
 */
public record PricingResult(
        List<Line> lines,
        BigDecimal subtotal,
        BigDecimal productDiscount,
        BigDecimal orderDiscount,
        BigDecimal rewardDiscount,
        BigDecimal deliveryFee,
        BigDecimal total,
        List<AppliedOffer> appliedOffers,
        CouponStatus couponStatus
) {
    public PricingResult {
        lines = List.copyOf(lines);
        appliedOffers = List.copyOf(appliedOffers);
    }

    /**
     * {@code lineTotal} = unit price × quantity (before the discount). {@code freeQuantity} (buy X get Y) is part
     * of {@code quantity}, and its value is in {@code discountAmount}. {@code appliedOfferId}: the item offer
     * used on this line, null if none.
     */
    public record Line(Long productId, int quantity, int freeQuantity, BigDecimal unitPrice,
                       BigDecimal discountAmount, BigDecimal lineTotal, Long appliedOfferId) {
    }

    public record AppliedOffer(Long offerId, PricingInput.OfferLevel level, BigDecimal discountAmount) {
    }

    public enum CouponStatus {
        /** No coupon offer in the input. */
        NONE,
        APPLIED,
        /** Below the coupon's minimum order amount (see {@link PricingInput.Offer#minOrderAmount}). */
        BELOW_MINIMUM,
        /** Item coupon: on none of its lines does it beat the automatic promotion already there. */
        NOT_BETTER
    }
}
