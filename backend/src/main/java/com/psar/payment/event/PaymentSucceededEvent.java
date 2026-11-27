package com.psar.payment.event;

import java.time.Instant;

/**
 * A gateway payment was confirmed for an order that is still waiting for it. Published <b>inside</b> the
 * confirming transaction: the order module handles it with a plain {@code @EventListener} (same transaction),
 * so "payment SUCCESS" and "order PAID" commit together. Notification listeners use AFTER_COMMIT. Ids only.
 */
public record PaymentSucceededEvent(Long paymentId, Long orderId, Instant paidAt) {
}
