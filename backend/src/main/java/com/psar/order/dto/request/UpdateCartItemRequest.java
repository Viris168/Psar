package com.psar.order.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Sets the line's quantity (1–99); remove the line with DELETE instead of setting 0. */
public record UpdateCartItemRequest(
        @NotNull @Min(1) @Max(99) Integer quantity
) {
}
