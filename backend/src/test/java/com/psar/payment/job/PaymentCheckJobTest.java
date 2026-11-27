package com.psar.payment.job;

import com.psar.catalog.entity.Product;
import com.psar.order.entity.Order;
import com.psar.order.job.OrderExpiryJob;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentEvent;
import com.psar.payment.entity.PaymentEventSource;
import com.psar.payment.entity.PaymentOutcome;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.repository.PaymentEventRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.PaymentFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCheckJobTest extends IntegrationTest {

    @Autowired
    private PaymentCheckJob job;

    @Autowired
    private PaymentFixtures payments;

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private OrderExpiryJob orderExpiryJob;

    private Product product;

    @BeforeEach
    void setUp() {
        product = fixtures.product(fixtures.category("Check job " + UUID.randomUUID()), "Kampot pepper", "4.00", 50, 0);
    }

    @Test
    void checkJob_paidAtBakong_confirms() {
        Order order = payments.khqrOrder(Map.of(product, 1));
        String bankRef = payments.payAtBakong(order);
        clock.advance(Duration.ofMinutes(2));

        runUntilChecked(order);

        Payment payment = payments.payment(order);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getTransactionRef()).isEqualTo(bankRef);
        assertThat(payment.getCheckAttempts()).isEqualTo(1);
        assertThat(paymentEventRepository.findAllByPaymentIdOrderByIdAsc(payment.getId())).singleElement()
                .satisfies(e -> {
                    assertThat(e.getSource()).isEqualTo(PaymentEventSource.CHECK_JOB);
                    assertThat(e.getOutcome()).isEqualTo(PaymentOutcome.APPLIED);
                });
    }

    @Test
    void checkJob_notPaid_onlyCounts() {
        Order order = payments.khqrOrder(Map.of(product, 1));
        clock.advance(Duration.ofMinutes(2));

        runUntilChecked(order);
        job.checkPayments(); // checked less than a minute ago: skipped
        Payment payment = payments.payment(order);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getCheckAttempts()).isEqualTo(1);
        assertThat(payment.getLastCheckedAt()).isNotNull();

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        runUntilChecked(order, 2);
        assertThat(payments.payment(order).getCheckAttempts()).isEqualTo(2);
        assertThat(paymentEventRepository.findAllByPaymentIdOrderByIdAsc(payment.getId())).isEmpty();
    }

    @Test
    void checkJob_tooNew_notChecked() {
        Order order = payments.khqrOrder(Map.of(product, 1));
        payments.payAtBakong(order);

        job.checkPayments();

        Payment payment = payments.payment(order);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getCheckAttempts()).isZero();
    }

    @Test
    void checkJob_paidAfterExpiry_late() {
        Order order = payments.khqrOrder(Map.of(product, 1));
        clock.advance(Duration.ofMinutes(16));
        orderExpiryJob.expireUnpaidOrders();
        payments.payAtBakong(order);

        runUntilChecked(order);

        Payment payment = payments.payment(order);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.isRefundRequired()).isTrue();
        assertThat(paymentEventRepository.findAllByPaymentIdOrderByIdAsc(payment.getId()))
                .extracting(PaymentEvent::getOutcome).containsExactly(PaymentOutcome.LATE);
    }

    @Test
    void checkJob_expiredLongAgo_notChecked() {
        Order order = payments.khqrOrder(Map.of(product, 1));
        clock.advance(Duration.ofMinutes(16));
        orderExpiryJob.expireUnpaidOrders();
        clock.advance(Duration.ofMinutes(31));

        for (int i = 0; i < 5; i++) {
            job.checkPayments();
        }

        assertThat(payments.payment(order).getCheckAttempts()).isZero();
    }

    private void runUntilChecked(Order order) {
        runUntilChecked(order, 1);
    }

    /** Payments left by other tests share the table: run until ours has been checked (each run takes 100). */
    private void runUntilChecked(Order order, int attempts) {
        for (int i = 0; i < 50 && payments.payment(order).getCheckAttempts() < attempts; i++) {
            job.checkPayments();
        }
    }
}
