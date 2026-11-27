package com.psar.payment.controller;

import com.psar.audit.service.AuditEntity;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.notification.entity.Notification;
import com.psar.notification.entity.NotificationType;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.repository.OrderRepository;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentEvent;
import com.psar.payment.entity.PaymentEventSource;
import com.psar.payment.entity.PaymentOutcome;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.event.LatePaymentEvent;
import com.psar.payment.repository.PaymentEventRepository;
import com.psar.payment.repository.PaymentRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminPaymentControllerTest extends IntegrationTest {

    private static final String URL = "/api/admin/payments";

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private PaymentFixtures payments;

    @Autowired
    private SystemFixtures system;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User admin;
    private Product rice;

    @BeforeEach
    void setUp() {
        admin = system.user(Role.ADMIN);
        Category category = fixtures.category("Admin payments");
        rice = fixtures.product(category, "Rice 1kg", "2.00", 50, 0);
    }

    @Test
    void latePayment_adminsNotified_markRefunded() throws Exception {
        User manager = system.user(Role.MANAGER);
        User staff = system.user(Role.STAFF);
        Order order = payments.khqrOrder(Map.of(rice, 1));
        Payment payment = payLate(order);

        String link = "/admin/orders/" + order.getPublicId();
        List<Notification> adminNotes = system.notifications(admin, NotificationType.PAYMENT_LATE);
        assertThat(adminNotes).extracting(Notification::getLink).containsExactly(link);
        assertThat(adminNotes.getFirst().getMessage()).contains(order.getOrderNo()).contains("by hand");
        assertThat(system.notifications(manager, NotificationType.PAYMENT_LATE)).hasSize(1);
        assertThat(system.notifications(staff, NotificationType.PAYMENT_LATE)).isEmpty();

        markRefunded(payment.getPublicId(), "Returned by ABA transfer", manager)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundRequired").value(false)) // off the to-do list once refunded
                .andExpect(jsonPath("$.refundReason").value("Returned by ABA transfer"))
                .andExpect(jsonPath("$.refundedBy.email").value(manager.getEmail()))
                .andExpect(jsonPath("$.refundedAt").exists());

        Payment saved = payments.payment(order);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(saved.getRefundedBy()).isEqualTo(manager.getId());
        assertThat(system.auditRows(AuditEntity.PAYMENT, payment.getPublicId().toString())).hasSize(1);

        markRefunded(payment.getPublicId(), "Again", manager)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void markRefunded_notRefundRequired_409() throws Exception {
        Order order = payments.khqrOrder(Map.of(rice, 1));

        markRefunded(payments.payment(order).getPublicId(), "Nothing to refund", admin)
                .andExpect(status().isConflict());

        assertThat(payments.payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void markRefunded_staff_403() throws Exception {
        Order order = payments.khqrOrder(Map.of(rice, 1));
        Payment payment = payLate(order);

        markRefunded(payment.getPublicId(), "No", system.user(Role.STAFF)).andExpect(status().isForbidden());
    }

    @Test
    void adminPayments_filterRefundRequired() throws Exception {
        Order late = payments.khqrOrder(Map.of(rice, 1));
        Payment latePayment = payLate(late);
        Order normal = payments.khqrOrder(Map.of(rice, 1));
        String normalId = payments.payment(normal).getPublicId().toString();

        mockMvc.perform(get(URL).with(TestAuth.as(admin))
                        .param("refundRequired", "true").param("status", "SUCCESS").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].publicId", hasItem(latePayment.getPublicId().toString())))
                .andExpect(jsonPath("$.content[*].publicId", not(hasItem(normalId))))
                .andExpect(jsonPath("$.content[*].refundRequired", everyItem(is(true))))
                .andExpect(jsonPath("$.content[0].orderNo").exists())
                .andExpect(jsonPath("$.content[0].id").doesNotExist());

        mockMvc.perform(get(URL).with(TestAuth.as(admin))
                        .param("refundRequired", "false").param("method", "KHQR").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].publicId", hasItem(normalId)))
                .andExpect(jsonPath("$.content[*].publicId", not(hasItem(latePayment.getPublicId().toString()))));
    }

    @Test
    void adminPayments_pageSizeCappedAt100() throws Exception {
        mockMvc.perform(get(URL).with(TestAuth.as(admin)).param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void adminPayment_detail_includesEventsWithPayload() throws Exception {
        Order order = payments.khqrOrder(Map.of(rice, 2));
        Payment payment = payments.payment(order);
        paymentEventRepository.save(PaymentEvent.builder()
                .paymentId(payment.getId())
                .source(PaymentEventSource.WEBHOOK)
                .provider(payment.getProvider())
                .providerRef(payment.getProviderRef())
                .transactionRef("TX-" + UUID.randomUUID())
                .amount(new BigDecimal("5.00"))
                .currency("USD")
                .payload(Map.of("hash", "abc"))
                .signatureValid(true)
                .outcome(PaymentOutcome.AMOUNT_MISMATCH)
                .build());

        mockMvc.perform(get(URL + "/" + payment.getPublicId()).with(TestAuth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(order.getOrderNo()))
                .andExpect(jsonPath("$.orderStatus").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.method").value("KHQR"))
                .andExpect(jsonPath("$.amount").value(5.00))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].outcome").value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.events[0].payload.hash").value("abc"))
                .andExpect(jsonPath("$.events[0].id").doesNotExist());
    }

    @Test
    void adminPayment_unknown_404() throws Exception {
        mockMvc.perform(get(URL + "/" + UUID.randomUUID()).with(TestAuth.as(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminPayments_staffOrCustomer_403() throws Exception {
        mockMvc.perform(get(URL).with(TestAuth.as(system.user(Role.STAFF)))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).with(TestAuth.as(system.user(Role.CUSTOMER)))).andExpect(status().isForbidden());
    }

    /**
     * What PaymentProcessor does on LATE: the order was cancelled, the money arrived anyway → payment SUCCESS
     * + refund_required, LatePaymentEvent, in one transaction.
     */
    private Payment payLate(Order order) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            Order loaded = orderRepository.findById(order.getId()).orElseThrow();
            loaded.setStatus(OrderStatus.CANCELLED);
            Payment payment = paymentRepository.lockByOrderId(order.getId()).orElseThrow();
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setRefundRequired(true);
            payment.setTransactionRef("TX-" + UUID.randomUUID());
            payment.setPaidAt(clock.instant());
            events.publishEvent(new LatePaymentEvent(payment.getId(), order.getId()));
        });
        return payments.payment(order);
    }

    private ResultActions markRefunded(UUID paymentId, String reason, User actor) throws Exception {
        return mockMvc.perform(post(URL + "/" + paymentId + "/refunded").with(TestAuth.as(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("reason", reason))));
    }
}
