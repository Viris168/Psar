package com.psar.notification.listener;

import com.psar.notification.entity.NotificationType;
import com.psar.notification.service.NotificationService;
import com.psar.order.dto.response.OrderSummary;
import com.psar.order.service.OrderLookupService;
import com.psar.payment.event.LatePaymentEvent;
import com.psar.payment.event.PaymentSucceededEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Payment events → in-app notifications, after the payment committed, in a new transaction: a failure here is
 * logged and never undoes the payment.
 * <ul>
 *   <li>KHQR order paid → {@code NEW_ORDER} to every active ADMIN, MANAGER and STAFF (the KHQR counterpart of
 *       the COD notification at checkout) and {@code ORDER_PAID} to the customer</li>
 *   <li>late payment (order already cancelled) → {@code PAYMENT_LATE} to ADMIN and MANAGER: refund by hand</li>
 * </ul>
 */
@Slf4j
@Component
public class PaymentNotificationListener {

    static final Set<String> STAFF_ROLES = Set.of("ADMIN", "MANAGER", "STAFF");
    static final Set<String> REFUND_ROLES = Set.of("ADMIN", "MANAGER");

    private final OrderLookupService orderLookupService;
    private final NotificationService notificationService;
    private final TransactionTemplate requiresNew;

    public PaymentNotificationListener(OrderLookupService orderLookupService,
                                       NotificationService notificationService,
                                       PlatformTransactionManager transactionManager) {
        this.orderLookupService = orderLookupService;
        this.notificationService = notificationService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> notifyPaid(event.orderId()));
        } catch (RuntimeException ex) {
            log.error("Paid-order notification failed for order {}", event.orderId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLatePayment(LatePaymentEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> notifyLate(event.orderId()));
        } catch (RuntimeException ex) {
            log.error("Late-payment notification failed for payment {}", event.paymentId(), ex);
        }
    }

    private void notifyPaid(Long orderId) {
        OrderSummary order = orderLookupService.summary(orderId);
        String amount = "$" + order.totalAmount().toPlainString();
        notificationService.notifyRoles(STAFF_ROLES, NotificationType.NEW_ORDER, "New order " + order.orderNo(),
                "KHQR order %s (%s), paid %s.".formatted(order.orderNo(),
                        OrderNotificationListener.label(order.deliveryType()), amount),
                "/admin/orders/" + order.publicId());
        notificationService.notifyUsers(List.of(order.userId()), NotificationType.ORDER_PAID,
                "Payment received for " + order.orderNo(),
                "We received your payment of %s for order %s. We are preparing it now.".formatted(
                        amount, order.orderNo()),
                "/orders/" + order.publicId());
    }

    private void notifyLate(Long orderId) {
        OrderSummary order = orderLookupService.summary(orderId);
        notificationService.notifyRoles(REFUND_ROLES, NotificationType.PAYMENT_LATE,
                "Late payment for " + order.orderNo(),
                ("Order %s was paid ($%s) after it was %s. Refund the customer by hand, then mark the payment "
                        + "refunded.").formatted(order.orderNo(), order.totalAmount().toPlainString(),
                                order.status().toLowerCase(Locale.ROOT)),
                "/admin/orders/" + order.publicId());
    }
}
