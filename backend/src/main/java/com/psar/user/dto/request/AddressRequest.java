package com.psar.user.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.psar.user.entity.DeliveryType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Used for both create and update. The cross-field checks mirror the DB constraints
 * so the client gets a 400 with a clear message instead of a constraint violation.
 * "CITY pin must be in Phnom Penh" and "delivery company is active" are checked in the service.
 */
public record AddressRequest(
        @Size(max = 30) String label,
        @NotBlank @Size(max = 100) String receiverName,
        @NotBlank @Size(max = 30) String phone,
        @NotNull DeliveryType deliveryType,
        @NotBlank @Size(max = 50) String province,

        // CITY (Phnom Penh)
        @DecimalMin("10.0") @DecimalMax("14.7") BigDecimal latitude,
        @DecimalMin("102.3") @DecimalMax("107.7") BigDecimal longitude,
        @Size(max = 1000) String mapAddress,
        @Size(max = 50) String district,
        @Size(max = 50) String commune,
        @Size(max = 30) String houseNo,
        @Size(max = 50) String street,
        @Size(max = 150) String landmark,

        // PROVINCE
        Long deliveryCompanyId,
        @Size(max = 150) String courierBranch,

        @Size(max = 1000) String note,
        boolean isDefault
) {

    @JsonIgnore
    @AssertTrue(message = "CITY addresses need a map pin (latitude and longitude)")
    public boolean isCityLocationValid() {
        return deliveryType != DeliveryType.CITY || (latitude != null && longitude != null);
    }

    @JsonIgnore
    @AssertTrue(message = "PROVINCE addresses need a delivery company and courier branch")
    public boolean isProvinceCourierValid() {
        return deliveryType != DeliveryType.PROVINCE
                || (deliveryCompanyId != null && courierBranch != null && !courierBranch.isBlank());
    }
}
