package com.psar.promotion.service;

import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.promotion.dto.request.UsedPromotion;
import com.psar.promotion.dto.response.OrderPromotionView;
import com.psar.promotion.entity.DiscountLevel;
import com.psar.promotion.entity.OrderPromotion;
import com.psar.promotion.entity.Promotion;
import com.psar.promotion.repository.OrderPromotionRepository;
import com.psar.promotion.repository.PromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Promotion usage: taken inside the checkout transaction, given back when the order is cancelled, expires or is
 * refunded (all three go through OrderWorkflow). Usage limits hold under concurrent checkouts because
 * {@code used_count} only changes through {@code PromotionRepository.claimUse / releaseUse}.
 */
@Service
@RequiredArgsConstructor
public class PromotionUsageService {

    private final PromotionRepository promotionRepository;
    private final OrderPromotionRepository orderPromotionRepository;
    private final Clock clock;

    /**
     * For each used promotion: {@code claimUse} (0 rows → 409 PROMOTION_EXHAUSTED, which rolls the whole
     * checkout back) and an {@code order_promotions} snapshot row (name, coupon code, level, discount, user).
     * Called by checkout after stock is reserved, in the same transaction. Promotions are claimed in id order,
     * so two checkouts using the same promotions take their row locks in the same order (no deadlock).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void claim(Long orderId, Long userId, List<UsedPromotion> used) {
        Instant now = clock.instant();
        List<UsedPromotion> sorted = used.stream().sorted(Comparator.comparing(UsedPromotion::promotionId)).toList();
        for (UsedPromotion use : sorted) {
            Promotion promotion = promotionRepository.findById(use.promotionId())
                    .orElseThrow(() -> exhausted("A promotion"));
            if (promotionRepository.claimUse(promotion.getId()) == 0) {
                throw exhausted(promotion.isCoupon() ? "Coupon " + promotion.getCouponCode()
                        : "Promotion " + promotion.getName());
            }
            orderPromotionRepository.save(OrderPromotion.of(orderId, userId, promotion,
                    DiscountLevel.valueOf(use.level()), use.discountAmount(), now));
        }
    }

    /**
     * Gives every use of this order back, once: sets {@code released_at} on its rows that have none and calls
     * {@code releaseUse} for each. Safe to call for an order without promotions or twice (the caller holds the
     * order's row lock, so two releases of one order can't run at the same time).
     * Called by OrderWorkflow when an order becomes CANCELLED (customer, staff, expiry, refund before delivery)
     * or REFUNDED.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long orderId) {
        Instant now = clock.instant();
        orderPromotionRepository.findAllByOrderIdOrderByIdAsc(orderId).stream()
                .filter(row -> !row.isReleased())
                .sorted(Comparator.comparing(OrderPromotion::getPromotionId))
                .forEach(row -> {
                    row.setReleasedAt(now);
                    orderPromotionRepository.save(row);
                    promotionRepository.releaseUse(row.getPromotionId());
                });
    }

    /** The promotions an order used, in the order they were applied (for order detail responses). */
    @Transactional(readOnly = true)
    public List<OrderPromotionView> forOrder(Long orderId) {
        return orderPromotionRepository.findAllByOrderIdOrderByIdAsc(orderId).stream()
                .map(row -> new OrderPromotionView(row.getPromotionName(), row.getCouponCode(),
                        row.getLevel().name(), row.getDiscountAmount(), row.isReleased()))
                .toList();
    }

    private static ConflictException exhausted(String what) {
        return new ConflictException(ErrorCode.PROMOTION_EXHAUSTED,
                what + " has just been used up; check your cart again");
    }
}
