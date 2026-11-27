package com.psar.payment.controller;

import com.psar.catalog.entity.Product;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.PaymentMethod;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentEventSource;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.repository.PaymentEventRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.OrderFixtures;
import com.psar.support.PaymentFixtures;
import com.psar.support.SystemFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentSimulationControllerTest extends PaymentWebTest {

    @Autowired
    private PaymentFixtures payments;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private SystemFixtures system;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    private Product product;

    @BeforeEach
    void setUp() {
        product = fixtures.product(fixtures.category("Simulate " + UUID.randomUUID()), "Lemongrass", "1.20", 50, 0);
    }

    @Test
    void simulate_ownOrder_paid() throws Exception {
        User customer = orders.customer();
        Order order = payments.khqrOrder(customer, Map.of(product, 1));
        Payment payment = payments.payment(order);

        mockMvc.perform(post(url(payment)).with(TestAuth.as(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(post(url(payment)).with(TestAuth.as(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));

        assertThat(payments.payment(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(paymentEventRepository.findAllByPaymentIdOrderByIdAsc(payment.getId()))
                .allSatisfy(e -> assertThat(e.getSource()).isEqualTo(PaymentEventSource.SIMULATED));
    }

    @Test
    void simulate_admin_anyOrder() throws Exception {
        Payment payment = payments.payment(payments.khqrOrder(Map.of(product, 1)));

        mockMvc.perform(post(url(payment)).with(TestAuth.as(system.user(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"));
    }

    @Test
    void simulate_otherCustomersOrder_404() throws Exception {
        Payment payment = payments.payment(payments.khqrOrder(Map.of(product, 1)));

        mockMvc.perform(post(url(payment)).with(TestAuth.as(orders.customer())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/payments/" + UUID.randomUUID() + "/simulate").with(TestAuth.as(orders.customer())))
                .andExpect(status().isNotFound());

        assertThat(paymentEventRepository.findAllByPaymentIdOrderByIdAsc(payment.getId())).isEmpty();
    }

    @Test
    void simulate_staffOrManager_403() throws Exception {
        Payment payment = payments.payment(payments.khqrOrder(Map.of(product, 1)));

        mockMvc.perform(post(url(payment)).with(TestAuth.as(system.user(Role.STAFF))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(url(payment)).with(TestAuth.as(system.user(Role.MANAGER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void simulate_cashOnDelivery_422() throws Exception {
        User customer = orders.customer();
        Order cod = orders.order(customer, orders.cityAddress(customer), PaymentMethod.CASH_ON_DELIVERY,
                OrderStatus.CONFIRMED, Map.of(product, 1));

        mockMvc.perform(post(url(payments.payment(cod))).with(TestAuth.as(customer)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void simulate_noLogin_401() throws Exception {
        Payment payment = payments.payment(payments.khqrOrder(Map.of(product, 1)));

        mockMvc.perform(post(url(payment))).andExpect(status().isUnauthorized());
    }

    private static String url(Payment payment) {
        return "/api/payments/" + payment.getPublicId() + "/simulate";
    }
}
