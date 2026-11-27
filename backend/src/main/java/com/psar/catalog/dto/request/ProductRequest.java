package com.psar.catalog.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Admin create/update. There is deliberately no {@code quantity}: stock only changes through purchase
 * orders and adjustments (inventory module), so every unit has a stock movement row.
 * {@code minStock}, {@code unit} and {@code active} are optional: omitted on create = 10 / "pcs" / active,
 * omitted on update = unchanged. {@code supplierPublicId} null = no supplier.
 */
public record ProductRequest(
        @NotBlank @Size(max = 50) String sku,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 150) String nameKh,
        @Size(max = 10_000) String description,
        @NotBlank @Size(max = 120) String categorySlug,
        UUID supplierPublicId,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal costPrice,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal sellPrice,
        @Min(0) Integer minStock,
        @Size(min = 1, max = 20) String unit,
        Boolean active
) {
}
