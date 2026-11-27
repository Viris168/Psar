package com.psar.catalog.dto.response;

import java.util.UUID;

/**
 * Stock view of a product for the inventory module (which may not use catalog entities).
 * {@code id} is internal: inventory stores it in stock_movements / purchase_order_items,
 * but it must never appear in an API response.
 */
public record ProductStockInfo(
        Long id,
        UUID publicId,
        String sku,
        String name,
        int quantity,
        int minStock,
        boolean active
) {
}
