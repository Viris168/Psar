package com.psar.order.dto.response;

import java.math.BigDecimal;

/** An order line as it was at checkout (snapshot). */
public record OrderItemResponse(
        String productName,
        String sku,
        int quantity,
        int freeQuantity,
        BigDecimal unitPrice,
        BigDecimal discountAmount,
        BigDecimal lineTotal
) {
}
