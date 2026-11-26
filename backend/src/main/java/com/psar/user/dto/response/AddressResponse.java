package com.psar.user.dto.response;

import com.psar.user.entity.DeliveryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AddressResponse(
        UUID publicId,
        String label,
        String receiverName,
        String phone,
        DeliveryType deliveryType,
        String province,
        BigDecimal latitude,
        BigDecimal longitude,
        String mapAddress,
        String district,
        String commune,
        String houseNo,
        String street,
        String landmark,
        Long deliveryCompanyId,
        String courierBranch,
        String note,
        boolean isDefault,
        Instant createdAt
) {
}
