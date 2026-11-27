package com.psar.payment.service.confirm;

import com.psar.catalog.entity.Product;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.repository.OrderRepository;
import com.psar.order.service.orders.OrderWorkflow;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentEventSource;
import com.psar.payment.entity.PaymentOutcome;
import com.psar.payment.entity.PaymentStatus;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.PaymentFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A payment arriving at the very moment its order expires. Confirmation and every cancel path lock
 * <b>order, then payment</b>, so the two never deadlock: one waits for the other, and the end state is always
 * consistent — PAID (expiry saw a paid order), or CANCELLED with the money flagged for refund (LATE).
 */
class PaymentExpiryRaceTest extends IntegrationTest {

    private static final int ROUNDS = 20;

    @Autowired
    private PaymentProcessor processor;

    @Autowired
    private OrderWorkflow workflow;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentFixtures payments;

    @Autowired
    private CatalogFixtures fixtures;

    @Test
    void confirmAndExpire_sameMoment_neverDeadlockAndAlwaysConsistent() throws Exception {
        Product product = fixtures.product(fixtures.category("Race " + UUID.randomUUID()), "Kampot pepper", "3.00",
                ROUNDS, 0);
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < ROUNDS; i++) {
            orders.add(payments.khqrOrder(Map.of(product, 1)));
        }
        clock.advance(Duration.ofMinutes(16)); // every order is now past expires_at
        int cancelled = 0;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (Order order : orders) {
                Payment payment = payments.payment(order);
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> expire = pool.submit(() -> {
                    start.await();
                    return workflow.expire(order.getId());
                });
                Future<PaymentOutcome> confirm = pool.submit(() -> {
                    start.await();
                    return processor.confirm(new PaymentConfirmation(PaymentEventSource.CHECK_JOB,
                            payment.getProvider(), payment.getProviderRef(), "TX-" + UUID.randomUUID(),
                            payment.getAmount(), payment.getCurrency(), clock.instant(), Map.of(), null));
                });
                start.countDown();

                // a deadlock would surface here as an ExecutionException (CannotAcquireLockException)
                boolean expired = expire.get(30, TimeUnit.SECONDS);
                PaymentOutcome outcome = confirm.get(30, TimeUnit.SECONDS);

                OrderStatus status = orderRepository.findById(order.getId()).orElseThrow().getStatus();
                Payment after = payments.payment(order);
                assertThat(after.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
                if (expired) {
                    cancelled++;
                    assertThat(outcome).isEqualTo(PaymentOutcome.LATE);
                    assertThat(status).isEqualTo(OrderStatus.CANCELLED);
                    assertThat(after.isRefundRequired()).isTrue();
                } else {
                    assertThat(outcome).isEqualTo(PaymentOutcome.APPLIED);
                    assertThat(status).isEqualTo(OrderStatus.PAID);
                    assertThat(after.isRefundRequired()).isFalse();
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // stock: one unit per round reserved, released once for every cancelled order
        assertThat(fixtures.quantityOf(product)).isEqualTo(cancelled);
    }
}
