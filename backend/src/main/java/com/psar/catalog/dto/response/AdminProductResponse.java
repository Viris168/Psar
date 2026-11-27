package com.psar.catalog.dto.response;

import com.psar.inventory.dto.response.SupplierSummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Staff/admin view of a product: includes cost price, supplier and exact stock. */
public record AdminProductResponse(
        UUID publicId,
        String sku,
        String slug,
        String name,
        String nameKh,
        String description,
        CategoryRef category,
        SupplierSummary supplier,
        BigDecimal costPrice,
        BigDecimal sellPrice,
        int quantity,
        int minStock,
        String unit,
        boolean active,
        String mainImageUrl,
        Instant createdAt,
        Instant updatedAt
) {
}
