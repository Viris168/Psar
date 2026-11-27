package com.psar.order.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.psar.order.entity.DeliveryType.CITY;
import static com.psar.order.entity.DeliveryType.PROVINCE;
import static com.psar.order.entity.OrderStatus.CANCELLED;
import static com.psar.order.entity.OrderStatus.CONFIRMED;
import static com.psar.order.entity.OrderStatus.DELIVERED;
import static com.psar.order.entity.OrderStatus.HANDED_TO_COURIER;
import static com.psar.order.entity.OrderStatus.OUT_FOR_DELIVERY;
import static com.psar.order.entity.OrderStatus.PAID;
import static com.psar.order.entity.OrderStatus.PENDING_PAYMENT;
import static com.psar.order.entity.OrderStatus.PROCESSING;
import static com.psar.order.entity.OrderStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;

/** The status flow of PRD §3.6, as a table. */
class OrderStatusTest {

    @Test
    void canMoveTo_khqrCityHappyPath_allowed() {
        assertThat(PENDING_PAYMENT.canMoveTo(PAID, CITY)).isTrue();
        assertThat(PAID.canMoveTo(PROCESSING, CITY)).isTrue();
        assertThat(PROCESSING.canMoveTo(OUT_FOR_DELIVERY, CITY)).isTrue();
        assertThat(OUT_FOR_DELIVERY.canMoveTo(DELIVERED, CITY)).isTrue();
        assertThat(DELIVERED.canMoveTo(REFUNDED, CITY)).isTrue();
    }

    @Test
    void canMoveTo_provinceUsesCourierNotDriver() {
        assertThat(PROCESSING.canMoveTo(HANDED_TO_COURIER, PROVINCE)).isTrue();
        assertThat(PROCESSING.canMoveTo(OUT_FOR_DELIVERY, PROVINCE)).isFalse();
        assertThat(PROCESSING.canMoveTo(HANDED_TO_COURIER, CITY)).isFalse();
        assertThat(HANDED_TO_COURIER.canMoveTo(DELIVERED, PROVINCE)).isTrue();
    }

    @Test
    void canMoveTo_skippingOrGoingBack_rejected() {
        assertThat(CONFIRMED.canMoveTo(OUT_FOR_DELIVERY, CITY)).isFalse();
        assertThat(CONFIRMED.canMoveTo(PAID, CITY)).isFalse();
        assertThat(PROCESSING.canMoveTo(CONFIRMED, CITY)).isFalse();
        assertThat(DELIVERED.canMoveTo(CANCELLED, CITY)).isFalse();
        assertThat(PENDING_PAYMENT.canMoveTo(PROCESSING, CITY)).isFalse();
    }

    @Test
    void cancel_onlyBeforeTheOrderLeavesTheShop() {
        assertThat(PENDING_PAYMENT.isCancellable()).isTrue();
        assertThat(CONFIRMED.isCancellable()).isTrue();
        assertThat(PAID.isCancellable()).isTrue();
        assertThat(PROCESSING.isCancellable()).isTrue();
        assertThat(OUT_FOR_DELIVERY.isCancellable()).isFalse();
        assertThat(HANDED_TO_COURIER.isCancellable()).isFalse();
        assertThat(DELIVERED.isCancellable()).isFalse();
        assertThat(CANCELLED.isCancellable()).isFalse();
    }

    @Test
    void customerCancel_onlyBeforeProcessing() {
        assertThat(PENDING_PAYMENT.isCancellableByCustomer()).isTrue();
        assertThat(CONFIRMED.isCancellableByCustomer()).isTrue();
        assertThat(PAID.isCancellableByCustomer()).isFalse();
        assertThat(PROCESSING.isCancellableByCustomer()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void canMoveTo_endStates_nothingFollowsExceptRefund(OrderStatus next) {
        assertThat(CANCELLED.canMoveTo(next, CITY)).isFalse();
        assertThat(REFUNDED.canMoveTo(next, CITY)).isFalse();
    }
}
