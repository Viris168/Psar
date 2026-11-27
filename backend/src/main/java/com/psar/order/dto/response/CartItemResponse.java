package com.psar.order.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A cart line at the product's <b>current</b> price.
 *
 * @param available the product can still be bought (product and its category active); checkout fails otherwise
 * @param inStock   at least one unit in stock (the stock number itself is never shown)
 */
public record CartItemResponse(
        UUID productPublicId,
        String slug,
        String name,
        String unit,
        String imageUrl,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal,
        boolean available,
        boolean inStock
) {
}
