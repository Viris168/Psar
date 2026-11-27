package com.psar.order.event;

/**
 * An order's status changed (published inside that transaction; listen AFTER_COMMIT). Statuses are the
 * {@code OrderStatus} names as strings, because other modules may not use order entities.
 */
public record OrderStatusChangedEvent(Long orderId, String from, String to, String note) {
}
