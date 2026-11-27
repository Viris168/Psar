package com.psar.order.listener;

import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.event.OrderStatusChangedEvent;
import com.psar.order.repository.OrderRepository;
import com.psar.order.service.orders.OrderWorkflow;
import com.psar.payment.event.PaymentSucceededEvent;
import com.psar.payment.service.admin.PaymentSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order side of payments, in the <b>same</b> transaction as the change that triggered it (plain
 * {@code @EventListener}), so both commit or roll back together:
 * <ul>
 *   <li>{@link PaymentSucceededEvent} → the order PENDING_PAYMENT → PAID (history row by the system, note
 *       "Paid by KHQR"). Not PENDING_PAYMENT any more → nothing (the processor already treated it as LATE).</li>
 *   <li>a cash-on-delivery order became DELIVERED → its payment SUCCESS ("cash collected").</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaymentListener {

    static final String PAID_NOTE = "Paid by KHQR";
    private static final String DELIVERED = OrderStatus.DELIVERED.name();

    private final OrderRepository orderRepository;
    private final OrderWorkflow workflow;
    private final PaymentSettlementService settlementService;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        Order order = orderRepository.lockById(event.orderId()).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            log.warn("Payment {} succeeded but order {} is not waiting for it ({}); left alone",
                    event.paymentId(), event.orderId(), order == null ? "missing" : order.getStatus());
            return;
        }
        workflow.move(order, OrderStatus.PAID, null, PAID_NOTE);
        log.info("Order {} paid at {}", order.getOrderNo(), event.paidAt());
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onStatusChanged(OrderStatusChangedEvent event) {
        if (DELIVERED.equals(event.to())) {
            settlementService.collectCash(event.orderId());
        }
    }
}
