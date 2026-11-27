package com.psar.catalog.repository;

import com.psar.catalog.dto.response.ProductStockInfo;
import com.psar.catalog.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    Optional<Product> findByPublicId(UUID publicId);

    Optional<Product> findBySlug(String slug);

    boolean existsBySku(String sku);

    boolean existsBySlug(String slug);

    // ---- catalog lists and pages -------------------------------------------------------------

    /** Search/filter lists: category and its parent come in the same query, so mapping has no N+1. */
    @Override
    @EntityGraph(attributePaths = {"category", "category.parent"})
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "category.parent"})
    Optional<Product> findWithCategoryByPublicId(UUID publicId);

    @EntityGraph(attributePaths = {"category", "category.parent"})
    Optional<Product> findWithCategoryBySlug(String slug);

    boolean existsBySkuAndIdNot(String sku, Long id);

    boolean existsByCategoryIdAndActiveTrue(Long categoryId);

    /** Main image URL for each product of one page (one query, no N+1). */
    @Query("""
            select i.product.id as productId, i.imageUrl as imageUrl
            from ProductImage i where i.main = true and i.product.id in :productIds""")
    List<MainImageRow> findMainImages(@Param("productIds") Collection<Long> productIds);

    /** All images of one product for the storefront page: main first, then by sort order. */
    @Query("""
            select i.imageUrl as url, i.main as main from ProductImage i
            where i.product.id = :productId order by i.main desc, i.sortOrder, i.id""")
    List<ImageRow> findImages(@Param("productId") Long productId);

    interface MainImageRow {
        Long getProductId();

        String getImageUrl();
    }

    interface ImageRow {
        String getUrl();

        Boolean getMain();
    }

    // ---- stock (Step 0: used only by ProductStockService; don't change) ----------------------

    @Query("""
            select new com.psar.catalog.dto.response.ProductStockInfo(
                p.id, p.publicId, p.sku, p.name, p.quantity, p.minStock, p.active)
            from Product p where p.publicId = :publicId""")
    Optional<ProductStockInfo> findStockInfoByPublicId(@Param("publicId") UUID publicId);

    @Query("""
            select new com.psar.catalog.dto.response.ProductStockInfo(
                p.id, p.publicId, p.sku, p.name, p.quantity, p.minStock, p.active)
            from Product p where p.id = :id""")
    Optional<ProductStockInfo> findStockInfoById(@Param("id") Long id);

    /** Reads the current value from the DB (not from an entity already loaded in this transaction). */
    @Query("select p.quantity from Product p where p.id = :id")
    Optional<Integer> findQuantityById(@Param("id") Long id);

    /** Atomic. Returns the number of rows updated (0 = product missing). */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.quantity = p.quantity + :qty where p.id = :id")
    int increaseQuantity(@Param("id") Long id, @Param("qty") int qty);

    /** Atomic and never below zero: returns 0 when there isn't enough stock (or the product is missing). */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.quantity = p.quantity - :qty where p.id = :id and p.quantity >= :qty")
    int decreaseQuantity(@Param("id") Long id, @Param("qty") int qty);

    @Query(value = """
            select new com.psar.catalog.dto.response.ProductStockInfo(
                p.id, p.publicId, p.sku, p.name, p.quantity, p.minStock, p.active)
            from Product p where p.active = true and p.quantity <= p.minStock""",
            countQuery = "select count(p) from Product p where p.active = true and p.quantity <= p.minStock")
    Page<ProductStockInfo> findLowStock(Pageable pageable);
}
