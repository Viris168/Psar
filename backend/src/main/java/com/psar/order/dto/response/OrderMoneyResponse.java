package com.psar.order.dto.response;

import java.math.BigDecimal;

/** The money breakdown: subtotal − discounts + delivery fee = total. */
public record OrderMoneyResponse(
        BigDecimal subtotal,
        BigDecimal productDiscount,
        BigDecimal orderDiscount,
        BigDecimal rewardDiscount,
        BigDecimal deliveryFee,
        BigDecimal totalAmount
) {
}
