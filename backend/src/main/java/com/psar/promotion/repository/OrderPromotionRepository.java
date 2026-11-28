package com.psar.promotion.repository;

import com.psar.promotion.entity.OrderPromotion;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OrderPromotionRepository extends JpaRepository<OrderPromotion, Long> {

    List<OrderPromotion> findAllByOrderIdOrderByIdAsc(Long orderId);

    /** This user's uses of a promotion that were not given back (per-user limit). */
    long countByPromotionIdAndUserIdAndReleasedAtIsNull(Long promotionId, Long userId);

    boolean existsByPromotionId(Long promotionId);

    /** This user's uses not given back, per promotion: rows of [promotionId, count]. */
    @Query("""
            SELECT op.promotionId, COUNT(op) FROM OrderPromotion op
            WHERE op.userId = :userId AND op.releasedAt IS NULL AND op.promotionId IN :promotionIds
            GROUP BY op.promotionId
            """)
    List<Object[]> countActiveUses(@Param("userId") Long userId,
                                   @Param("promotionIds") Collection<Long> promotionIds);
}
