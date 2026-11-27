package com.psar.catalog.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A product as cart and checkout see it (the order module may not use catalog entities).
 * {@code id} is internal: the order module stores it, but it never appears in an API response.
 * {@code quantity} is for checks only; customers see {@code inStock}, never the number.
 *
 * @param purchasable the product, its category and the category's parent are all active
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
        boolean purchasable
) {
    public boolean inStock() {
        return quantity > 0;
    }
}
