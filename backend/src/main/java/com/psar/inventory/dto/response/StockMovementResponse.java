package com.psar.inventory.dto.response;

import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.entity.MovementType;

import java.time.Instant;

/**
 * One line of a product's stock history. {@code quantity} is signed (+ added, − removed).
 * {@code referenceNo} is the purchase order number for PURCHASE movements, otherwise null.
 */
public record StockMovementResponse(
        InventoryProductResponse product,
        MovementType type,
        int quantity,
        int balanceAfter,
        MovementReason reason,
        String referenceNo,
        String note,
        Instant createdAt
) {
}
