package com.psar.catalog.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A product as cart and checkout see it (the order module may not use catalog entities).
 * {@code id} is internal: the order module stores it, but it never appears in an API response.
 * {@code quantity} is for checks only; customers see {@code inStock}, never the number.
 *
 * @param categoryId       internal, for promotion targets
 * @param parentCategoryId the category's parent (null for a top-level category), so a promotion on a top-level
 *                         category also covers products in its subcategories
 * @param purchasable      the product, its category and the category's parent are all active
 */
public record SellableProduct(
        Long id,
        UUID publicId,
        String slug,
        String sku,
        String name,
        BigDecimal sellPrice,
        String unit,
        String mainImageUrl,
        int quantity,
        Long categoryId,
        Long parentCategoryId,
        boolean purchasable
) {
    public boolean inStock() {
        return quantity > 0;
    }
}
