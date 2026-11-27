package com.psar.support;

import com.psar.catalog.entity.Product;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.PaymentMethod;
import com.psar.payment.entity.Payment;
import com.psar.payment.repository.PaymentRepository;
import com.psar.payment.service.bakong.FakeBakongClient;
import com.psar.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;

import java.util.Map;
import java.util.UUID;

/**
 * KHQR orders with their payment, and "the customer paid" at the fake Bakong.
 * Available in every IntegrationTest: {@code @Autowired PaymentFixtures payments;}
 */
@TestComponent
@RequiredArgsConstructor
public class PaymentFixtures {

    private final OrderFixtures orders;
    private final PaymentRepository paymentRepository;
    private final FakeBakongClient fakeBakong;

    /**
     * A PENDING_PAYMENT KHQR order for a new customer, stock reserved. Its payment always has a provider
     * reference: the real QR md5 once KhqrService makes codes, otherwise a test md5 (so confirmation can be
     * tested before Agent A's work is merged).
     */
    public Order khqrOrder(Map<Product, Integer> lines) {
        User customer = orders.customer();
        return khqrOrder(customer, lines);
    }

    public Order khqrOrder(User customer, Map<Product, Integer> lines) {
        Order order = orders.order(customer, orders.cityAddress(customer), PaymentMethod.KHQR,
                OrderStatus.PENDING_PAYMENT, lines);
        Payment payment = payment(order);
        if (payment.getProviderRef() == null) {
            String md5 = UUID.randomUUID().toString().replace("-", "");
            payment.setProviderRef(md5);
            payment.setQrMd5(md5);
            paymentRepository.save(payment);
        }
        return order;
    }

    public Payment payment(Order order) {
        return paymentRepository.findByOrderId(order.getId()).orElseThrow();
    }

    /** The customer pays the full amount: the fake Bakong now reports it paid. Returns the bank reference. */
    public String payAtBakong(Order order) {
        Payment payment = payment(order);
        return fakeBakong.markPaid(payment.getProviderRef(), payment.getAmount(), payment.getCurrency());
    }
}
