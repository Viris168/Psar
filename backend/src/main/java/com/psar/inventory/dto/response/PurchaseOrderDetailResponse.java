package com.psar.inventory.dto.response;

import com.psar.inventory.entity.PurchaseOrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PurchaseOrderDetailResponse(
        String poNo,
        SupplierSummary supplier,
        PurchaseOrderStatus status,
        BigDecimal totalAmount,
        String note,
        Instant createdAt,
        Instant receivedAt,
        Instant cancelledAt,
        List<PurchaseOrderItemResponse> items
) {
}
