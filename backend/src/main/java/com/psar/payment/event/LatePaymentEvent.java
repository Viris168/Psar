package com.psar.payment.event;

/** Money arrived for an order that was already cancelled or expired: someone must refund it by hand. */
public record LatePaymentEvent(Long paymentId, Long orderId) {
}
