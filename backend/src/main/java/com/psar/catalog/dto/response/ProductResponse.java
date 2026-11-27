package com.psar.catalog.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Storefront list item. Never contains the cost price, the supplier or the exact quantity: only {@code inStock}.
 */
public record ProductResponse(
        UUID publicId,
        String slug,
        String name,
        String nameKh,
        BigDecimal sellPrice,
        String unit,
        boolean inStock,
        String mainImageUrl,
        CategoryRef category
) {
}
