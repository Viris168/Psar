package com.psar.promotion.repository;

import com.psar.promotion.entity.OrderPromotion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderPromotionRepository extends JpaRepository<OrderPromotion, Long> {

    List<OrderPromotion> findAllByOrderIdOrderByIdAsc(Long orderId);

    /** This user's uses of a promotion that were not given back (per-user limit). */
    long countByPromotionIdAndUserIdAndReleasedAtIsNull(Long promotionId, Long userId);

    boolean existsByPromotionId(Long promotionId);
}
