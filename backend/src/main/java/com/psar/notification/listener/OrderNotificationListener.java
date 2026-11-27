package com.psar.notification.listener;

import com.psar.notification.entity.NotificationType;
import com.psar.notification.service.NotificationService;
import com.psar.order.dto.response.OrderSummary;
import com.psar.order.event.OrderPlacedEvent;
import com.psar.order.event.OrderStatusChangedEvent;
import com.psar.order.service.OrderLookupService;
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
 * Order events → in-app notifications, after the order change committed, in a new transaction: a failure here
 * is logged and never undoes the order change.
 * <ul>
 *   <li>COD order placed → {@code NEW_ORDER} to every active ADMIN, MANAGER and STAFF (paid KHQR orders: Phase 5)</li>
 *   <li>status changed → the customer: {@code ORDER_STATUS_CHANGED}, or {@code ORDER_CANCELLED} with the reason</li>
 * </ul>
 */
@Slf4j
@Component
public class OrderNotificationListener {

    static final Set<String> STAFF_ROLES = Set.of("ADMIN", "MANAGER", "STAFF");
    static final String COD = "CASH_ON_DELIVERY";
    static final String CANCELLED = "CANCELLED";
    private static final int TITLE_MAX = 150;
    private static final int MESSAGE_MAX = 1000;

    private final OrderLookupService orderLookupService;
    private final NotificationService notificationService;
    private final TransactionTemplate requiresNew;

    public OrderNotificationListener(OrderLookupService orderLookupService, NotificationService notificationService,
                                     PlatformTransactionManager transactionManager) {
        this.orderLookupService = orderLookupService;
        this.notificationService = notificationService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> notifyStaff(event.orderId()));
        } catch (RuntimeException ex) {
            log.error("New-order notification failed for order {}", event.orderId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(OrderStatusChangedEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> notifyCustomer(event));
        } catch (RuntimeException ex) {
            log.error("Status notification failed for order {} ({} → {})", event.orderId(), event.from(),
                    event.to(), ex);
        }
    }

    private void notifyStaff(Long orderId) {
        OrderSummary order = orderLookupService.summary(orderId);
        if (!COD.equals(order.paymentMethod())) {
            return; // KHQR: staff hear about it once it is paid (Phase 5)
        }
        String title = "New order " + order.orderNo();
        String message = "Cash on delivery order %s (%s), total $%s.".formatted(
                order.orderNo(), label(order.deliveryType()), order.totalAmount().toPlainString());
        notificationService.notifyRoles(STAFF_ROLES, NotificationType.NEW_ORDER, title, message,
                "/admin/orders/" + order.publicId());
    }

    private void notifyCustomer(OrderStatusChangedEvent event) {
        OrderSummary order = orderLookupService.summary(event.orderId());
        String link = "/orders/" + order.publicId();
        if (CANCELLED.equals(event.to())) {
            String message = "Your order %s was cancelled".formatted(order.orderNo())
                    + (event.note() == null ? "." : ": " + event.note());
            notificationService.notifyUsers(List.of(order.userId()), NotificationType.ORDER_CANCELLED,
                    truncate("Order " + order.orderNo() + " cancelled", TITLE_MAX), truncate(message, MESSAGE_MAX),
                    link);
            return;
        }
        String title = "Order %s: %s".formatted(order.orderNo(), label(event.to()));
        String message = "Your order %s is now %s.".formatted(order.orderNo(),
                label(event.to()).toLowerCase(Locale.ROOT));
        notificationService.notifyUsers(List.of(order.userId()), NotificationType.ORDER_STATUS_CHANGED,
                truncate(title, TITLE_MAX), truncate(message, MESSAGE_MAX), link);
    }

    /** {@code OUT_FOR_DELIVERY} → "Out for delivery". */
    static String label(String enumName) {
        String words = enumName.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
