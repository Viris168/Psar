package com.psar.inventory.dto.response;

import java.util.UUID;

/** Active product at or below its minimum stock. */
public record LowStockResponse(
        UUID publicId,
        String sku,
        String name,
        int quantity,
        int minStock
) {
}
