package com.psar.inventory.event;

/**
 * A stock change took the product from above its minimum to at or below it. Published by StockService inside
 * the stock transaction; listen with {@code @TransactionalEventListener(AFTER_COMMIT)}. Carries only the id:
 * reload the product (StockService.product) for current numbers.
 */
public record StockLowEvent(Long productId) {
}
