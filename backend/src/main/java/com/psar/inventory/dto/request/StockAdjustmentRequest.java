package com.psar.inventory.dto.request;

import com.psar.inventory.entity.MovementReason;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Manual stock change. {@code change} is signed (−3 = three damaged, +2 = two returned) and not 0.
 * {@code reason} must be DAMAGE, RETURN or CORRECTION.
 */
public record StockAdjustmentRequest(
        @NotNull UUID productPublicId,
        @NotNull @Min(-1_000_000) @Max(1_000_000) Integer change,
        @NotNull MovementReason reason,
        @Size(max = 255) String note
) {
}
