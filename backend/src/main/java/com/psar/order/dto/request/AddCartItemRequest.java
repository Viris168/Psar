package com.psar.order.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Adds {@code quantity} to the line for this product (creating it); a line never goes above 99. */
public record AddCartItemRequest(
        @NotNull UUID productPublicId,
        @NotNull @Min(1) @Max(99) Integer quantity
) {
}
