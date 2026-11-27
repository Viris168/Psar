package com.psar.order.dto.request;

import com.psar.order.entity.OrderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Staff moves an order one step on, e.g. {@code PROCESSING}. Cancelling has its own endpoint
 * (ADMIN / MANAGER only); PAID and REFUNDED are set by payments.
 */
public record ChangeOrderStatusRequest(
        @NotNull OrderStatus status,
        @Size(max = 500) String note
) {
}
