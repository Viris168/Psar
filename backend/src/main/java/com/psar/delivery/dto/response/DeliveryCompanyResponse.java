package com.psar.delivery.dto.response;

import java.math.BigDecimal;

public record DeliveryCompanyResponse(
        Long id,
        String name,
        String logoUrl,
        String phone,
        String trackingUrl,
        BigDecimal baseFee,
        boolean active
) {
}
