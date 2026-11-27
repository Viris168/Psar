package com.psar.order.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Refund an order. The reason is shown to the customer. {@code restock} only matters for DELIVERED orders
 * (the goods came back and can be sold again); a paid order that never left the shop always gets its stock back.
 */
public record RefundOrderRequest(@NotBlank @Size(max = 300) String reason, boolean restock) {
}
