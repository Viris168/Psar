package com.psar.catalog.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Storefront product page. Never contains the cost price, the supplier or the exact quantity: only {@code inStock}.
 * {@code breadcrumb} goes from the top-level category down to the product's own category.
 * {@code images}: main image first, then by sort order.
 */
public record ProductDetailResponse(
        UUID publicId,
        String slug,
        String sku,
        String name,
        String nameKh,
        String description,
        BigDecimal sellPrice,
        String unit,
        boolean inStock,
        List<Image> images,
        List<CategoryRef> breadcrumb
) {

    public record Image(
            String url,
            boolean main
    ) {
    }
}
