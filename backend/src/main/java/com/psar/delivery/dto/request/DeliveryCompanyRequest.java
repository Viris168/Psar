package com.psar.delivery.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.math.BigDecimal;

/**
 * Admin create/update. {@code active} is optional: omitted on create = active,
 * omitted on update = unchanged (MapStruct skips null wrappers for primitive targets).
 */
public record DeliveryCompanyRequest(
        @NotBlank @Size(max = 100) String name,
        @URL @Size(max = 255) String logoUrl,
        @Size(max = 30) String phone,
        // template, e.g. https://courier.example/track/{trackingNo}
        @Size(max = 255) String trackingUrl,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal baseFee,
        Boolean active
) {
}
