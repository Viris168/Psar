package com.psar.promotion.service;

import com.psar.promotion.dto.request.PromotionCartLine;
import com.psar.promotion.dto.response.OfferSet;
import com.psar.promotion.entity.Promotion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Which promotions can apply to a cart, for the checkout quote and checkout (the order module calls this; it may
 * not read promotion tables itself).
 */
@Service
@RequiredArgsConstructor
public class PromotionService {

    /**
     * Every promotion that can apply to these lines for this user at {@code now}:
     * <ul>
     *   <li>automatic ones that are active, in their dates, with uses left (usage limit and this user's
     *       per-user limit, counting only uses that were not given back) and matching at least one line;
     *       ITEM offers list exactly the matching product ids (CATEGORIES: the category or its parent)</li>
     *   <li>members-only promotions are skipped (Phase 8 has members)</li>
     *   <li>the coupon, looked up case-insensitively, if it passes the same checks; otherwise
     *       {@link OfferSet#couponProblem} says why (unknown, not started, ended, used up, already used by
     *       you, members only, nothing in the cart it applies to)</li>
     * </ul>
     * The minimum order amount is left to pricing (it depends on item discounts).
     * <p>
     * Step 0 version: no offers (prices stay as in Phase 5). Agent C fills it in.
     *
     * @param couponCode as the customer typed it; null / blank = none
     */
    @Transactional(readOnly = true)
    public OfferSet offersFor(Long userId, List<PromotionCartLine> lines, String couponCode, Instant now) {
        return OfferSet.none(Promotion.normaliseCode(couponCode));
    }
}
