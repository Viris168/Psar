package com.psar.payment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.catalog.entity.Product;
import com.psar.order.entity.Order;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.repository.OrderRepository;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentProvider;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.repository.PaymentRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.support.PaymentFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Address;
import com.psar.user.entity.User;
import kh.gov.nbc.bakong_khqr.BakongKHQR;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerPaymentControllerTest extends IntegrationTest {

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private PaymentFixtures payments;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void checkout_khqr_returnsQrAndStoresMd5() throws Exception {
        Product product = fixtures.product(fixtures.category("KHQR checkout"), "Coffee", "3.25", 10, 0);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(product, 2));

        JsonNode body = body(checkout(customer, address, PaymentMethod.KHQR).andExpect(status().isCreated()));

        JsonNode payment = body.get("payment");
        assertThat(payment.get("status").asText()).isEqualTo("PENDING");
        assertThat(payment.get("method").asText()).isEqualTo("KHQR");
        assertThat(payment.get("currency").asText()).isEqualTo("USD");
        assertThat(payment.get("amount").decimalValue()).isEqualByComparingTo(body.get("totalAmount").decimalValue());
        assertThat(payment.get("expiresAt").asText()).isEqualTo(body.get("expiresAt").asText());
        String qr = payment.get("qrString").asText();
        assertThat(BakongKHQR.verify(qr).getData().isValid()).isTrue();
        var decoded = BakongKHQR.decode(qr).getData();
        assertThat(decoded.getBillNumber()).isEqualTo(body.get("orderNo").asText());
        assertThat(new BigDecimal(decoded.getTransactionAmount()))
                .isEqualByComparingTo(body.get("totalAmount").decimalValue());
        assertNoSecrets(payment);

        Order order = orderRepository.findByPublicId(UUID.fromString(body.get("publicId").asText())).orElseThrow();
        Payment saved = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(saved.getQrString()).isEqualTo(qr);
        assertThat(saved.getQrMd5()).hasSize(32).isEqualTo(saved.getProviderRef());
        assertThat(saved.getProvider()).isEqualTo(PaymentProvider.BAKONG_KHQR);
        assertThat(saved.getExpiresAt()).isEqualTo(order.getExpiresAt());
    }

    @Test
    void checkout_cod_noQr() throws Exception {
        Product product = fixtures.product(fixtures.category("COD checkout"), "Bread", "1.00", 10, 0);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(product, 1));

        JsonNode body = body(checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY)
                .andExpect(status().isCreated()));

        JsonNode payment = body.get("payment");
        assertThat(payment.get("method").asText()).isEqualTo("CASH_ON_DELIVERY");
        assertThat(payment.get("status").asText()).isEqualTo("PENDING");
        assertThat(payment.get("qrString").isNull()).isTrue();
        Order order = orderRepository.findByPublicId(UUID.fromString(body.get("publicId").asText())).orElseThrow();
        Payment saved = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(saved.getQrString()).isNull();
        assertThat(saved.getQrMd5()).isNull();
        assertThat(saved.getProviderRef()).isNull();
    }

    @Test
    void payment_ownKhqrOrder_returnsQr() throws Exception {
        Product product = fixtures.product(fixtures.category("Pay own"), "Milk", "2.00", 10, 0);
        User customer = orders.customer();
        Order order = payments.khqrOrder(customer, Map.of(product, 1));

        JsonNode payment = body(getPayment(customer, order).andExpect(status().isOk()));

        assertThat(payment.get("status").asText()).isEqualTo("PENDING");
        assertThat(payment.get("amount").decimalValue()).isEqualByComparingTo(order.getTotalAmount());
        assertThat(payment.get("qrString").asText()).isEqualTo(payments.payment(order).getQrString());
        assertThat(payment.get("paidAt").isNull()).isTrue();
    }

    @Test
    void payment_otherCustomersOrder_returns404() throws Exception {
        Product product = fixtures.product(fixtures.category("Pay other"), "Eggs", "2.00", 10, 0);
        Order order = payments.khqrOrder(Map.of(product, 1));
        User stranger = orders.customer();

        getPayment(stranger, order)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/orders/{id}/payment", UUID.randomUUID()).with(TestAuth.as(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void payment_staff_403() throws Exception {
        Product product = fixtures.product(fixtures.category("Pay staff"), "Jam", "2.00", 10, 0);
        Order order = payments.khqrOrder(Map.of(product, 1));

        mockMvc.perform(get("/api/orders/{id}/payment", order.getPublicId()).with(TestAuth.asRole(1L, "STAFF")))
                .andExpect(status().isForbidden());
    }

    @Test
    void payment_expired_hidesQr() throws Exception {
        Product product = fixtures.product(fixtures.category("Pay expired"), "Tofu", "1.00", 10, 0);
        User customer = orders.customer();
        Order order = payments.khqrOrder(customer, Map.of(product, 1));

        clock.advance(Duration.ofMinutes(16));
        JsonNode payment = body(getPayment(customer, order).andExpect(status().isOk()));

        assertThat(payment.get("status").asText()).isEqualTo("PENDING");
        assertThat(payment.get("qrString").isNull()).isTrue();
        assertThat(payment.get("expiresAt").isNull()).isFalse();
    }

    @Test
    void payment_paid_hidesQrShowsPaidAt() throws Exception {
        Product product = fixtures.product(fixtures.category("Pay paid"), "Rice", "5.00", 10, 0);
        User customer = orders.customer();
        Order order = payments.khqrOrder(customer, Map.of(product, 1));
        Payment saved = payments.payment(order);
        Instant paidAt = clock.instant();
        saved.setStatus(PaymentStatus.SUCCESS);
        saved.setPaidAt(paidAt);
        saved.setTransactionRef("BANK-" + UUID.randomUUID());
        paymentRepository.save(saved);

        JsonNode payment = body(getPayment(customer, order).andExpect(status().isOk()));

        assertThat(payment.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(payment.get("qrString").isNull()).isTrue();
        assertThat(payment.get("paidAt").isNull()).isFalse();
    }

    @Test
    void payment_neverShowsMd5OrRef() throws Exception {
        Product product = fixtures.product(fixtures.category("Pay secrets"), "Sugar", "1.50", 10, 0);
        User customer = orders.customer();
        Order order = payments.khqrOrder(customer, Map.of(product, 1));
        Payment saved = payments.payment(order);
        saved.setTransactionRef("BANK-SECRET-" + UUID.randomUUID());
        paymentRepository.save(saved);

        String raw = getPayment(customer, order).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(raw).doesNotContain(saved.getQrMd5()).doesNotContain(saved.getTransactionRef());
        assertNoSecrets(objectMapper.readTree(raw));
    }

    private static void assertNoSecrets(JsonNode payment) {
        assertThat(payment.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "status", "method", "amount", "currency", "expiresAt", "qrString", "paidAt");
    }

    private ResultActions getPayment(User customer, Order order) throws Exception {
        return mockMvc.perform(get("/api/orders/{id}/payment", order.getPublicId()).with(TestAuth.as(customer)));
    }

    private ResultActions checkout(User customer, Address address, PaymentMethod method) throws Exception {
        return mockMvc.perform(post("/api/checkout").with(TestAuth.as(customer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("addressPublicId", address.getPublicId(), "paymentMethod", method))));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
