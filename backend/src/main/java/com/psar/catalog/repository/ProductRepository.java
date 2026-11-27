package com.psar.catalog.repository;

import com.psar.catalog.dto.response.ProductStockInfo;
import com.psar.catalog.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByPublicId(UUID publicId);

    Optional<Product> findBySlug(String slug);

    boolean existsBySku(String sku);

    boolean existsBySlug(String slug);

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
