package com.psar.order.listener;

import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.notification.entity.Notification;
import com.psar.notification.entity.NotificationType;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.OrderStatusHistory;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.repository.OrderRepository;
import com.psar.order.repository.OrderStatusHistoryRepository;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.event.PaymentSucceededEvent;
import com.psar.payment.repository.PaymentRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.support.PaymentFixtures;
import com.psar.support.SystemFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The order side of payments: PaymentSucceededEvent → PAID (+ notifications), COD delivered → cash collected. */
class OrderPaymentListenerTest extends IntegrationTest {

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private PaymentFixtures payments;

    @Autowired
    private SystemFixtures system;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderStatusHistoryRepository historyRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Product rice;

    @BeforeEach
    void setUp() {
        Category category = fixtures.category("Payment listener");
        rice = fixtures.product(category, "Rice 5kg", "6.00", 20, 0);
    }

    @Test
    void paymentSucceeded_pendingOrder_paidWithHistory() {
        Order order = payments.khqrOrder(Map.of(rice, 2));

        confirm(order);

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        List<OrderStatusHistory> history = historyRepository.findAllByOrderIdOrderByIdAsc(order.getId());
        assertThat(history).hasSize(2);
        OrderStatusHistory paid = history.get(1);
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.getChangedBy()).isNull();
        assertThat(paid.getNote()).isEqualTo(OrderPaymentListener.PAID_NOTE);
    }

    @Test
    void paymentSucceeded_staffAndCustomerNotified() {
        User staff = system.user(Role.STAFF);
        User customer = orders.customer();
        Order order = payments.khqrOrder(customer, Map.of(rice, 1));

        confirm(order);

        String adminLink = "/admin/orders/" + order.getPublicId();
        assertThat(system.notifications(staff, NotificationType.NEW_ORDER))
                .extracting(Notification::getLink).containsExactly(adminLink);
        List<Notification> paid = system.notifications(customer, NotificationType.ORDER_PAID);
        assertThat(paid).hasSize(1);
        assertThat(paid.getFirst().getLink()).isEqualTo("/orders/" + order.getPublicId());
        assertThat(paid.getFirst().getMessage()).contains(order.getOrderNo()).contains("$7.00");
    }

    @Test
    void paymentSucceeded_orderAlreadyCancelled_orderUnchanged() {
        Order order = payments.khqrOrder(Map.of(rice, 1));
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            Order loaded = orderRepository.findById(order.getId()).orElseThrow();
            loaded.setStatus(OrderStatus.CANCELLED);
        });

        confirm(order);

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(historyRepository.findAllByOrderIdOrderByIdAsc(order.getId())).hasSize(1);
    }

    @Test
    void codDelivered_paymentSuccess() throws Exception {
        User staff = system.user(Role.STAFF);
        User customer = orders.customer();
        Order order = orders.order(customer, orders.cityAddress(customer), PaymentMethod.CASH_ON_DELIVERY,
                OrderStatus.OUT_FOR_DELIVERY, Map.of(rice, 1));
        assertThat(payments.payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING);

        mockMvc.perform(patch("/api/admin/orders/" + order.getPublicId() + "/status").with(TestAuth.as(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "DELIVERED"))))
                .andExpect(status().isOk());

        Payment payment = payments.payment(order);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getPaidAt()).isCloseTo(clock.instant(), within(1, ChronoUnit.SECONDS));
    }

    @Test
    void khqrDelivered_paymentUntouched() throws Exception {
        User staff = system.user(Role.STAFF);
        User customer = orders.customer();
        Order order = orders.order(customer, orders.cityAddress(customer), PaymentMethod.KHQR,
                OrderStatus.OUT_FOR_DELIVERY, Map.of(rice, 1));

        mockMvc.perform(patch("/api/admin/orders/" + order.getPublicId() + "/status").with(TestAuth.as(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "DELIVERED"))))
                .andExpect(status().isOk());

        assertThat(payments.payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    /** What PaymentProcessor does on APPLIED: payment SUCCESS + the event, in one transaction. */
    private void confirm(Order order) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            Payment payment = paymentRepository.lockByOrderId(order.getId()).orElseThrow();
            Instant paidAt = clock.instant();
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setTransactionRef("TX-" + UUID.randomUUID());
            payment.setPaidAt(paidAt);
            events.publishEvent(new PaymentSucceededEvent(payment.getId(), order.getId(), paidAt));
        });
    }
}
