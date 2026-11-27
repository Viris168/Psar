package com.psar.payment.controller;

import com.psar.catalog.entity.Product;
import com.psar.order.entity.Order;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentEvent;
import com.psar.payment.entity.PaymentOutcome;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.gateway.KhqrWebhookParser;
import com.psar.payment.repository.PaymentEventRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.PaymentFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentWebhookControllerTest extends PaymentWebTest {

    private static final String URL = "/api/payments/webhook/bakong-khqr";

    @Autowired
    private PaymentFixtures payments;

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    private Product product;

    @BeforeEach
    void setUp() {
        product = fixtures.product(fixtures.category("Webhook " + UUID.randomUUID()), "Jasmine rice", "3.25", 50, 0);
    }

    @Test
    void webhook_validSignature_200Applied() throws Exception {
        Order order = payments.khqrOrder(Map.of(product, 2));
        Payment payment = payments.payment(order);
        byte[] body = body(payment, "TX-" + UUID.randomUUID());

        send(body, KhqrWebhookParser.sign(body, WEBHOOK_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"));

        Payment after = payments.payment(order);
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(paymentEventRepository.findAllByPaymentIdOrderByIdAsc(after.getId())).singleElement()
                .satisfies(e -> assertThat(e.getSignatureValid()).isTrue());
    }

    @Test
    void webhook_badSignature_401() throws Exception {
        Order order = payments.khqrOrder(Map.of(product, 1));
        Payment payment = payments.payment(order);
        byte[] body = body(payment, "TX-" + UUID.randomUUID());

        send(body, KhqrWebhookParser.sign(body, "wrong-secret"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(payments.payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING);
        // the forged body is not trusted, so the rows aren't linked to the payment; find them by their flag
        assertThat(paymentEventRepository.findAll()).filteredOn(e -> Boolean.FALSE.equals(e.getSignatureValid()))
                .hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(e -> {
                    assertThat(e.getOutcome()).isEqualTo(PaymentOutcome.REJECTED);
                    assertThat(e.getPaymentId()).isNull();
                });
        assertThat(paymentEventRepository.findAllByPaymentIdOrderByIdAsc(payment.getId())).isEmpty();
    }

    @Test
    void webhook_duplicate_200NoChange() throws Exception {
        Order order = payments.khqrOrder(Map.of(product, 1));
        Payment payment = payments.payment(order);
        byte[] body = body(payment, "TX-" + UUID.randomUUID());
        String signature = KhqrWebhookParser.sign(body, WEBHOOK_SECRET);

        send(body, signature).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        Payment first = payments.payment(order);
        send(body, signature).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));

        Payment second = payments.payment(order);
        assertThat(second.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(second.getPaidAt()).isEqualTo(first.getPaidAt());
        assertThat(second.getTransactionRef()).isEqualTo(first.getTransactionRef());
        assertThat(paymentEventRepository.findAllByPaymentIdOrderByIdAsc(payment.getId()))
                .extracting(PaymentEvent::getOutcome).containsExactly(PaymentOutcome.APPLIED, PaymentOutcome.DUPLICATE);
    }

    @Test
    void webhook_amountMismatch_200PaymentUntouched() throws Exception {
        Order order = payments.khqrOrder(Map.of(product, 1));
        Payment payment = payments.payment(order);
        byte[] body = ("{\"md5\":\"" + payment.getProviderRef() + "\",\"hash\":\"TX-" + UUID.randomUUID()
                + "\",\"amount\":0.01,\"currency\":\"USD\"}").getBytes(StandardCharsets.UTF_8);

        send(body, KhqrWebhookParser.sign(body, WEBHOOK_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("AMOUNT_MISMATCH"));
        assertThat(payments.payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void webhook_unknownProvider_404() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/payments/webhook/paypal").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(KhqrWebhookParser.SIGNATURE_HEADER, KhqrWebhookParser.sign(body, WEBHOOK_SECRET)))
                .andExpect(status().isNotFound());
    }

    @Test
    void webhook_bodyOver64Kb_413() throws Exception {
        byte[] body = new byte[64 * 1024 + 1];
        java.util.Arrays.fill(body, (byte) 'a');

        send(body, KhqrWebhookParser.sign(body, WEBHOOK_SECRET)).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void webhook_signedButNotJson_400() throws Exception {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);

        send(body, KhqrWebhookParser.sign(body, WEBHOOK_SECRET)).andExpect(status().isBadRequest());
    }

    private ResultActions send(byte[] body, String signature) throws Exception {
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body)
                .header(KhqrWebhookParser.SIGNATURE_HEADER, signature));
    }

    private static byte[] body(Payment payment, String transactionRef) {
        return ("{\"md5\":\"" + payment.getProviderRef() + "\",\"hash\":\"" + transactionRef + "\",\"amount\":"
                + payment.getAmount().toPlainString() + ",\"currency\":\"USD\"}").getBytes(StandardCharsets.UTF_8);
    }
}
