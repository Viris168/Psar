package com.psar.promotion.dto.response;

import java.util.List;
import java.util.Optional;

/**
 * What {@code PromotionService.offersFor} found: every automatic promotion that can apply, plus the entered
 * coupon if it can. A coupon that can't be used is not in {@code offers}; {@code couponProblem} says why
 * (for the customer). The minimum order amount is not checked here: it depends on item discounts, so pricing
 * checks it.
 *
 * @param couponCode    the entered code, normalised (trimmed, upper-case); null if none was entered
 * @param couponProblem null if no code was entered or the coupon is in {@code offers}
 */
public record OfferSet(List<PromotionOffer> offers, String couponCode, String couponProblem) {

    public OfferSet {
        offers = List.copyOf(offers);
    }

    public static OfferSet none(String couponCode) {
        return new OfferSet(List.of(), couponCode, null);
    }

    public Optional<PromotionOffer> coupon() {
        return offers.stream().filter(PromotionOffer::isCoupon).findFirst();
    }
}
