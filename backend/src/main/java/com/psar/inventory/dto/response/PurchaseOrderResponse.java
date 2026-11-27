package com.psar.inventory.dto.response;

import com.psar.inventory.entity.PurchaseOrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** Purchase order in a list (no items). */
public record PurchaseOrderResponse(
        String poNo,
        SupplierSummary supplier,
        PurchaseOrderStatus status,
        BigDecimal totalAmount,
        String note,
        Instant createdAt,
        Instant receivedAt,
        Instant cancelledAt
) {
}
