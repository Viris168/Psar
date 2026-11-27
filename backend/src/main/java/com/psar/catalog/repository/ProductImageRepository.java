package com.psar.catalog.repository;

import com.psar.catalog.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    /** All images of a product in display order. */
    List<ProductImage> findAllByProductIdOrderBySortOrderAscIdAsc(Long productId);

    /** Main images of a page of products (one query, no N+1). */
    List<ProductImage> findAllByProductIdInAndMainTrue(Collection<Long> productIds);

    Optional<ProductImage> findByPublicIdAndProductId(UUID publicId, Long productId);

    long countByProductId(Long productId);

    /**
     * Locks the product row (SELECT ... FOR UPDATE) so image changes of one product run one at a time:
     * keeps the 10-image limit and "one main image" correct under concurrent requests.
     */
    @Query(value = "select id from products where public_id = :publicId for update", nativeQuery = true)
    Optional<Long> lockProductIdByPublicId(@Param("publicId") UUID publicId);
}
