package com.psar.notification.entity;

/** Points and subscription types come with their phases. */
public enum NotificationType {
    LOW_STOCK,
    /** Staff: a new order to prepare (COD now; paid KHQR orders from Phase 5). */
    NEW_ORDER,
    /** Customer: their order moved on (processing, out for delivery, delivered, …). */
    ORDER_STATUS_CHANGED,
    /** Customer: their order was cancelled (by them, staff, or not paid in time). */
    ORDER_CANCELLED,
    /** Customer: their KHQR payment was received. */
    ORDER_PAID,
    /** Admins / managers: money arrived for a cancelled order, refund it by hand. */
    PAYMENT_LATE,
    /** Customers: a promotion was announced (by a manager, or when it started). */
    PROMOTION
}
