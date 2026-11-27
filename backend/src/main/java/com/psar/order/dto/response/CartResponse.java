package com.psar.order.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * The customer's cart at current prices. {@code subtotal} counts only lines that are still {@code available};
 * delivery fee and total come from the checkout quote.
 */
public record CartResponse(
        List<CartItemResponse> items,
        int totalQuantity,
        BigDecimal subtotal
) {
    public CartResponse {
        items = List.copyOf(items);
    }
}
