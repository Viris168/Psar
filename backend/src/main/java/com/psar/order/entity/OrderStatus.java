package com.psar.order.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order status flow (PRD §3.6). {@link #canMoveTo} is the single source of truth for allowed changes.
 * <pre>
 * KHQR: PENDING_PAYMENT → PAID → PROCESSING → OUT_FOR_DELIVERY (CITY) / HANDED_TO_COURIER (PROVINCE) → DELIVERED
 * COD:  CONFIRMED → PROCESSING → OUT_FOR_DELIVERY → DELIVERED (CITY only)
 * Before it leaves the shop → CANCELLED; DELIVERED → REFUNDED (Phase 5)
 * </pre>
 */
public enum OrderStatus {
    PENDING_PAYMENT, CONFIRMED, PAID, PROCESSING, OUT_FOR_DELIVERY, HANDED_TO_COURIER, DELIVERED, CANCELLED, REFUNDED;

    private static final Map<OrderStatus, Set<OrderStatus>> NEXT = Map.of(
            PENDING_PAYMENT, EnumSet.of(PAID, CANCELLED),
            CONFIRMED, EnumSet.of(PROCESSING, CANCELLED),
            PAID, EnumSet.of(PROCESSING, CANCELLED),
            PROCESSING, EnumSet.of(OUT_FOR_DELIVERY, HANDED_TO_COURIER, CANCELLED),
            OUT_FOR_DELIVERY, EnumSet.of(DELIVERED),
            HANDED_TO_COURIER, EnumSet.of(DELIVERED),
            DELIVERED, EnumSet.of(REFUNDED),
            CANCELLED, EnumSet.noneOf(OrderStatus.class),
            REFUNDED, EnumSet.noneOf(OrderStatus.class));

    /** Whether an order of this delivery type may go from this status to {@code next}. */
    public boolean canMoveTo(OrderStatus next, DeliveryType deliveryType) {
        if (!NEXT.get(this).contains(next)) {
            return false;
        }
        return switch (next) {
            case OUT_FOR_DELIVERY -> deliveryType == DeliveryType.CITY;
            case HANDED_TO_COURIER -> deliveryType == DeliveryType.PROVINCE;
            default -> true;
        };
    }

    /** Still at the shop: the order can be cancelled and its stock released. */
    public boolean isCancellable() {
        return NEXT.get(this).contains(CANCELLED);
    }

    /** The customer may cancel only before the shop starts working on it. */
    public boolean isCancellableByCustomer() {
        return this == PENDING_PAYMENT || this == CONFIRMED;
    }
}
