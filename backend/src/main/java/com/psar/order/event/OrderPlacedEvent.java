package com.psar.order.event;

/** Checkout created an order. Published inside the checkout transaction; listen AFTER_COMMIT. Ids only. */
public record OrderPlacedEvent(Long orderId) {
}
