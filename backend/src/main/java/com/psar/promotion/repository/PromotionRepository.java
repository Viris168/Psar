package com.psar.promotion.repository;

import com.psar.promotion.entity.Promotion;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PromotionRepository extends JpaRepository<Promotion, Long>, JpaSpecificationExecutor<Promotion> {

    Optional<Promotion> findByPublicId(UUID publicId);

    @EntityGraph(attributePaths = "targets")
    Optional<Promotion> findWithTargetsById(Long id);

    /** {@code code} already normalised ({@link Promotion#normaliseCode}). */
    Optional<Promotion> findByCouponCode(String code);

    boolean existsByCouponCode(String code);

    /**
     * Takes one use if any are left (like the stock update): 1 = claimed, 0 = used up. The only way
     * {@code used_count} goes up. The row lock is held until the checkout transaction ends.
     * Doesn't clear the persistence context (checkout still holds its cart and order), so a loaded
     * {@link Promotion} keeps its old {@code usedCount}.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE promotions SET used_count = used_count + 1, updated_at = NOW()
            WHERE id = :id AND (usage_limit IS NULL OR used_count < usage_limit)
            """, nativeQuery = true)
    int claimUse(@Param("id") Long id);

    /** Gives one use back (cancel / expiry / refund); never below 0. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE promotions SET used_count = used_count - 1, updated_at = NOW()
            WHERE id = :id AND used_count > 0
            """, nativeQuery = true)
    int releaseUse(@Param("id") Long id);
}
