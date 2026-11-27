package com.psar.catalog.service;

import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Locale;

/** Building blocks for product search. Each returns null when the filter is not set (Spring ignores it). */
final class ProductSpecifications {

    private static final char ESCAPE = '\\';

    private ProductSpecifications() {
    }

    /** Storefront: active product in an active category whose parent (if any) is active too. */
    static Specification<Product> visibleInStore() {
        return (root, query, cb) -> {
            Join<Product, Category> category = root.join("category");
            Join<Category, Category> parent = category.join("parent", JoinType.LEFT);
            return cb.and(
                    cb.isTrue(root.get("active")),
                    cb.isTrue(category.get("active")),
                    cb.or(cb.isNull(parent.get("id")), cb.isTrue(parent.get("active"))));
        };
    }

    /** Case-insensitive match on name, Khmer name or SKU. */
    static Specification<Product> matches(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String pattern = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, ESCAPE),
                cb.like(cb.lower(root.get("nameKh")), pattern, ESCAPE),
                cb.like(cb.lower(root.get("sku")), pattern, ESCAPE));
    }

    static Specification<Product> inCategories(Collection<Long> categoryIds) {
        if (categoryIds == null) {
            return null;
        }
        return (root, query, cb) -> root.get("category").get("id").in(categoryIds);
    }

    static Specification<Product> priceAtLeast(BigDecimal min) {
        return min == null ? null : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("sellPrice"), min);
    }

    static Specification<Product> priceAtMost(BigDecimal max) {
        return max == null ? null : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("sellPrice"), max);
    }

    static Specification<Product> inStock(Boolean inStock) {
        if (inStock == null) {
            return null;
        }
        return (root, query, cb) -> inStock
                ? cb.greaterThan(root.get("quantity"), 0)
                : cb.equal(root.get("quantity"), 0);
    }

    static Specification<Product> supplier(Long supplierId) {
        return supplierId == null ? null : (root, query, cb) -> cb.equal(root.get("supplierId"), supplierId);
    }

    static Specification<Product> active(Boolean active) {
        return active == null ? null : (root, query, cb) -> cb.equal(root.get("active"), active);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
