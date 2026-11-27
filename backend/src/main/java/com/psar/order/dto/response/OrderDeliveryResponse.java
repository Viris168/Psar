package com.psar.order.dto.response;

import java.math.BigDecimal;

/**
 * The delivery snapshot taken at checkout. {@code trackingUrl} is the company's link with the tracking
 * number filled in (PROVINCE orders with a tracking number only).
 */
public record OrderDeliveryResponse(
        String deliveryType,
        String receiverName,
        String receiverPhone,
        String province,
        String shippingAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        String landmark,
        String deliveryCompanyName,
        String courierBranch,
        String trackingNo,
        String trackingUrl
) {
}
