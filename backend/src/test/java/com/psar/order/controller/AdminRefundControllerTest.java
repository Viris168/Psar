package com.psar.order.controller;

import com.psar.audit.entity.AuditLog;
import com.psar.audit.service.AuditEntity;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.entity.MovementType;
import com.psar.inventory.repository.StockMovementRepository;
import com.psar.notification.entity.NotificationType;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.repository.OrderRepository;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentStatus;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminRefundControllerTest extends IntegrationTest {

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
    private PaymentRepository paymentRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    private User manager;
    private User customer;
    private Product rice;

    @BeforeEach
    void setUp() {
        manager = system.user(Role.MANAGER);
        customer = orders.customer();
        Category category = fixtures.category("Refunds");
        rice = fixtures.product(category, "Rice 10kg", "12.00", 10, 0);
    }

    @Test
    void refund_paidOrder_cancelledStockBackPaymentRefunded() throws Exception {
        int before = fixtures.quantityOf(rice);
        Order order = paidKhqr(OrderStatus.PAID, 3);
        assertThat(fixtures.quantityOf(rice)).isEqualTo(before - 3);

        refund(order, "Customer changed their mind", false, manager)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(fixtures.quantityOf(rice)).isEqualTo(before);
        Payment payment = payments.payment(order);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isNotNull();
        assertThat(payment.getRefundedBy()).isEqualTo(manager.getId());
        assertThat(payment.getRefundReason()).isEqualTo("Customer changed their mind");

        List<AuditLog> audit = system.auditRows(AuditEntity.PAYMENT, payment.getPublicId().toString());
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().getUserId()).isEqualTo(manager.getId());
        assertThat(system.notifications(customer, NotificationType.ORDER_CANCELLED)).hasSize(1);
    }

    @Test
    void refund_processingOrder_cancelled() throws Exception {
        Order order = paidKhqr(OrderStatus.PROCESSING, 1);

        refund(order, "Out of stock at the supplier", false, manager)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void refund_deliveredWithoutRestock_stockUnchanged() throws Exception {
        Order order = paidKhqr(OrderStatus.DELIVERED, 2);
        int afterDelivery = fixtures.quantityOf(rice);

        refund(order, "Damaged on arrival", false, manager)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));

        assertThat(fixtures.quantityOf(rice)).isEqualTo(afterDelivery);
        assertThat(releases(order)).isZero();
        assertThat(system.notifications(customer, NotificationType.ORDER_STATUS_CHANGED)).hasSize(1);
    }

    @Test
    void refund_deliveredWithRestock_stockBack() throws Exception {
        int before = fixtures.quantityOf(rice);
        Order order = paidKhqr(OrderStatus.DELIVERED, 2);

        refund(order, "Returned unopened", true, manager).andExpect(status().isOk());

        assertThat(fixtures.quantityOf(rice)).isEqualTo(before);
        assertThat(releases(order)).isEqualTo(1);
    }

    @Test
    void refund_deliveredCod_refunded() throws Exception {
        Order order = orders.order(customer, orders.cityAddress(customer), PaymentMethod.CASH_ON_DELIVERY,
                OrderStatus.DELIVERED, Map.of(rice, 1));
        markPaid(order);

        refund(order, "Wrong item", false, manager)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void refund_twice_409() throws Exception {
        int before = fixtures.quantityOf(rice);
        Order order = paidKhqr(OrderStatus.PAID, 2);

        refund(order, "First", false, manager).andExpect(status().isOk());
        refund(order, "Second", false, manager)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        assertThat(fixtures.quantityOf(rice)).isEqualTo(before);
        assertThat(releases(order)).isEqualTo(1);
        assertThat(payments.payment(order).getRefundReason()).isEqualTo("First");
    }

    @Test
    void refund_deliveredTwice_409() throws Exception {
        Order order = paidKhqr(OrderStatus.DELIVERED, 1);

        refund(order, "First", true, manager).andExpect(status().isOk());
        refund(order, "Second", true, manager).andExpect(status().isConflict());

        assertThat(releases(order)).isEqualTo(1);
    }

    @Test
    void refund_pendingPayment_409() throws Exception {
        Order order = payments.khqrOrder(customer, Map.of(rice, 1));

        refund(order, "Nothing to refund", false, manager)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        assertThat(payments.payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void refund_codNotDelivered_409() throws Exception {
        Order order = orders.order(customer, orders.cityAddress(customer), PaymentMethod.CASH_ON_DELIVERY,
                OrderStatus.PROCESSING, Map.of(rice, 1));

        refund(order, "Not paid yet", false, manager).andExpect(status().isConflict());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PROCESSING);
    }

    @Test
    void refund_staff_403() throws Exception {
        Order order = paidKhqr(OrderStatus.PAID, 1);

        refund(order, "Not allowed", false, system.user(Role.STAFF)).andExpect(status().isForbidden());

        assertThat(payments.payment(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void refund_blankReason_400() throws Exception {
        Order order = paidKhqr(OrderStatus.PAID, 1);

        refund(order, " ", false, manager).andExpect(status().isBadRequest());
    }

    @Test
    void refund_unknownOrder_404() throws Exception {
        mockMvc.perform(post("/api/admin/orders/" + UUID.randomUUID() + "/refund").with(TestAuth.as(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "x", "restock", false))))
                .andExpect(status().isNotFound());
    }

    private Order paidKhqr(OrderStatus status, int qty) {
        Order order = orders.order(customer, orders.cityAddress(customer), PaymentMethod.KHQR, status,
                Map.of(rice, qty));
        markPaid(order);
        return order;
    }

    private void markPaid(Order order) {
        Payment payment = payments.payment(order);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setTransactionRef("TX-" + UUID.randomUUID());
        payment.setPaidAt(clock.instant());
        paymentRepository.save(payment);
    }

    private long releases(Order order) {
        return stockMovementRepository.findAll().stream()
                .filter(m -> order.getId().equals(m.getReferenceId()))
                .filter(m -> m.getType() == MovementType.IN && m.getReason() == MovementReason.RELEASE)
                .count();
    }

    private ResultActions refund(Order order, String reason, boolean restock, User actor) throws Exception {
        return mockMvc.perform(post("/api/admin/orders/" + order.getPublicId() + "/refund").with(TestAuth.as(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("reason", reason, "restock", restock))));
    }
}
