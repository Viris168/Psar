package com.psar.delivery.dto.response;

import java.math.BigDecimal;

/** What checkout needs from a delivery company. {@code trackingUrl} contains {@code {tracking_no}}. */
public record DeliveryCompanySnapshot(
        Long id,
        String name,
        BigDecimal baseFee,
        String trackingUrl,
        boolean active
) {
}
