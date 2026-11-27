package com.psar.order.service.delivery;

import com.psar.delivery.dto.response.DeliveryCompanySnapshot;

import java.math.BigDecimal;

/** The delivery fee for an address, and the company (PROVINCE only, else null). */
public record DeliveryQuote(BigDecimal fee, DeliveryCompanySnapshot company) {
}
