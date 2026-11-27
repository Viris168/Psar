package com.psar.inventory.dto.response;

import java.math.BigDecimal;

public record PurchaseOrderItemResponse(
        InventoryProductResponse product,
        int quantity,
        BigDecimal unitCost,
        BigDecimal lineTotal
) {
}
