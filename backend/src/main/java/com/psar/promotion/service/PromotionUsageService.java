package com.psar.promotion.service;

import com.psar.promotion.dto.request.UsedPromotion;
import com.psar.promotion.dto.response.OrderPromotionView;
import com.psar.promotion.repository.OrderPromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Promotion usage: taken inside the checkout transaction, given back when the order is cancelled, expires or is
 * refunded (all three go through OrderWorkflow). Usage limits hold under concurrent checkouts because
 * {@code used_count} only changes through {@code PromotionRepository.claimUse / releaseUse}.
 */
@Service
@RequiredArgsConstructor
public class PromotionUsageService {

    private final OrderPromotionRepository orderPromotionRepository;

    /**
     * For each used promotion: {@code claimUse} (0 rows → 409 PROMOTION_EXHAUSTED, which rolls the whole
     * checkout back) and an {@code order_promotions} snapshot row (name, coupon code, level, discount, user).
     * Called by checkout after stock is reserved, in the same transaction.
     * <p>
     * Step 0 version: does nothing. Agent C fills it in.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void claim(Long orderId, Long userId, List<UsedPromotion> used) {
        // Step 0: no promotions apply yet
    }

    /**
     * Gives every use of this order back, once: sets {@code released_at} on its rows that have none and calls
     * {@code releaseUse} for each. Safe to call for an order without promotions or twice.
     * Called by OrderWorkflow when an order becomes CANCELLED (customer, staff, expiry, refund before delivery)
     * or REFUNDED.
     * <p>
     * Step 0 version: does nothing. Agent C fills it in.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long orderId) {
        // Step 0: nothing was claimed
    }

    /** The promotions an order used, in the order they were applied (for order detail responses). */
    @Transactional(readOnly = true)
    public List<OrderPromotionView> forOrder(Long orderId) {
        return orderPromotionRepository.findAllByOrderIdOrderByIdAsc(orderId).stream()
                .map(row -> new OrderPromotionView(row.getPromotionName(), row.getCouponCode(),
                        row.getLevel().name(), row.getDiscountAmount(), row.isReleased()))
                .toList();
    }
}
