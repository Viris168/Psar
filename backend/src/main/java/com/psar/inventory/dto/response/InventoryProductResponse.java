package com.psar.inventory.dto.response;

import java.util.UUID;

/** Product as shown inside inventory responses (movements, purchase order items). */
public record InventoryProductResponse(
        UUID publicId,
        String sku,
        String name
) {
}
