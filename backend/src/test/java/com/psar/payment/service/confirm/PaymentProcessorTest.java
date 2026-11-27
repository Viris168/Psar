package com.psar.payment.service.confirm;

import com.psar.catalog.entity.Product;
import com.psar.order.entity.Order;
import com.psar.order.job.OrderExpiryJob;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentEvent;
import com.psar.payment.entity.PaymentEventSource;
import com.psar.payment.entity.PaymentOutcome;
import com.psar.payment.entity.PaymentProvider;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.event.LatePaymentEvent;
import com.psar.payment.event.PaymentSucceededEvent;
import com.psar.payment.repository.PaymentEventRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.PaymentFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The confirmation rules. The order side (PENDING_PAYMENT → PAID on {@link PaymentSucceededEvent}) is the order
 * module's listener; here we check the payment and that the event was published.
 */
@RecordApplicationEvents
class PaymentProcessorTest extends IntegrationTest {

    @Autowired
    private PaymentProcessor processor;

    @Autowired
    private PaymentFixtures payments;

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private OrderExpiryJob orderExpiryJob;

    @Autowired
    private ApplicationEvents applicationEvents;

    private Product product;

    @BeforeEach
    void setUp() {
        product = fixtures.product(fixtures.category("Confirm " + UUID.randomUUID()), "Palm sugar", "2.50", 50, 0);
    }

    @Test
    void confirm_pendingPayment_successAndPaidEventPublished() {
        Order order = payments.khqrOrder(Map.of(product, 2));
        Payment before = payments.payment(order);

        PaymentOutcome outcome = processor.confirm(paid(before, "TX-" + UUID.randomUUID(), PaymentEventSource.WEBHOOK));

        assertThat(outcome).isEqualTo(PaymentOutcome.APPLIED);
        Payment after = payments.payment(order);
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(after.getTransactionRef()).startsWith("TX-");
        assertThat(after.getPaidAt()).isNotNull();
        assertThat(after.isRefundRequired()).isFalse();
        assertThat(after.getRawWebhook()).containsEntry("test", true);
        assertThat(applicationEvents.stream(PaymentSucceededEvent.class))
                .singleElement().satisfies(e -> {
                    assertThat(e.paymentId()).isEqualTo(after.getId());
                    assertThat(e.orderId()).isEqualTo(order.getId());
                });
        assertThat(events(after)).extracting(PaymentEvent::getOutcome).containsExactly(PaymentOutcome.APPLIED);
    }

    @Test
    void confirm_twice_secondIsDuplicateNoChange() {
        Order order = payments.khqrOrder(Map.of(product, 1));
        Payment payment = payments.payment(order);
        PaymentConfirmation confirmation = paid(payment, "TX-" + UUID.randomUUID(), PaymentEventSource.CHECK_JOB);

        assertThat(processor.confirm(confirmation)).isEqualTo(PaymentOutcome.APPLIED);
        Payment first = payments.payment(order);
        assertThat(processor.confirm(confirmation)).isEqualTo(PaymentOutcome.DUPLICATE);

        Payment second = payments.payment(order);
        assertThat(second.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(second.getTransactionRef()).isEqualTo(first.getTransactionRef());
        assertThat(second.getPaidAt()).isEqualTo(first.getPaidAt());
        assertThat(applicationEvents.stream(PaymentSucceededEvent.class)).hasSize(1);
        assertThat(events(second)).extracting(PaymentEvent::getOutcome)
                .containsExactly(PaymentOutcome.APPLIED, PaymentOutcome.DUPLICATE);
    }

    @Test
    void confirm_concurrentWebhookAndJob_appliedExactlyOnce() throws Exception {
        Order order = payments.khqrOrder(Map.of(product, 1));
        Payment payment = payments.payment(order);
        String ref = "TX-" + UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<PaymentOutcome>> sources = new ArrayList<>();
        for (PaymentEventSource source : List.of(PaymentEventSource.WEBHOOK, PaymentEventSource.CHECK_JOB)) {
            sources.add(() -> {
                start.await();
                return processor.confirm(paid(payment, ref, source));
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<PaymentOutcome> outcomes = new ArrayList<>();
        try {
            List<Future<PaymentOutcome>> futures = sources.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<PaymentOutcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).containsExactlyInAnyOrder(PaymentOutcome.APPLIED, PaymentOutcome.DUPLICATE);
        assertThat(payments.payment(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(events(payment)).extracting(PaymentEvent::getOutcome)
                .containsExactly(PaymentOutcome.APPLIED, PaymentOutcome.DUPLICATE);
    }

    @Test
    void confirm_amountMismatch_paymentUntouched() {
        Order order = payments.khqrOrder(Map.of(product, 2));
        Payment payment = payments.payment(order);
        PaymentConfirmation tooLittle = new PaymentConfirmation(PaymentEventSource.WEBHOOK,
                PaymentProvider.BAKONG_KHQR, payment.getProviderRef(), "TX-" + UUID.randomUUID(),
                payment.getAmount().subtract(new BigDecimal("0.01")), "USD", clock.instant(), Map.of(), true);
        PaymentConfirmation wrongCurrency = new PaymentConfirmation(PaymentEventSource.CHECK_JOB,
                PaymentProvider.BAKONG_KHQR, payment.getProviderRef(), "TX-" + UUID.randomUUID(),
                payment.getAmount(), "KHR", clock.instant(), Map.of(), null);

        assertThat(processor.confirm(tooLittle)).isEqualTo(PaymentOutcome.AMOUNT_MISMATCH);
        assertThat(processor.confirm(wrongCurrency)).isEqualTo(PaymentOutcome.AMOUNT_MISMATCH);

        Payment after = payments.payment(order);
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(after.getTransactionRef()).isNull();
        assertThat(after.getPaidAt()).isNull();
        assertThat(applicationEvents.stream(PaymentSucceededEvent.class)).isEmpty();
        assertThat(events(after)).extracting(PaymentEvent::getOutcome)
                .containsExactly(PaymentOutcome.AMOUNT_MISMATCH, PaymentOutcome.AMOUNT_MISMATCH);
    }

    @Test
    void confirm_amountWithMoreScale_sameValueApplied() {
        Order order = payments.khqrOrder(Map.of(product, 2));
        Payment payment = payments.payment(order);
        PaymentConfirmation confirmation = new PaymentConfirmation(PaymentEventSource.CHECK_JOB,
                PaymentProvider.BAKONG_KHQR, payment.getProviderRef(), "TX-" + UUID.randomUUID(),
                payment.getAmount().setScale(4), "usd", null, Map.of(), null);

        assertThat(processor.confirm(confirmation)).isEqualTo(PaymentOutcome.APPLIED);
    }

    @Test
    void confirm_unknownMd5_eventRecorded() {
        String md5 = UUID.randomUUID().toString().replace("-", "");

        PaymentOutcome outcome = processor.confirm(new PaymentConfirmation(PaymentEventSource.WEBHOOK,
                PaymentProvider.BAKONG_KHQR, md5, "TX-" + UUID.randomUUID(), new BigDecimal("1.00"), "USD",
                clock.instant(), Map.of("md5", md5), true));

        assertThat(outcome).isEqualTo(PaymentOutcome.UNKNOWN_PAYMENT);
        assertThat(paymentEventRepository.findAllByProviderRefOrderByIdAsc(md5)).singleElement().satisfies(e -> {
            assertThat(e.getOutcome()).isEqualTo(PaymentOutcome.UNKNOWN_PAYMENT);
            assertThat(e.getPaymentId()).isNull();
            assertThat(e.getSource()).isEqualTo(PaymentEventSource.WEBHOOK);
        });
    }

    @Test
    void confirm_badSignature_rejectedNothingChanged() {
        Order order = payments.khqrOrder(Map.of(product, 1));
        Payment payment = payments.payment(order);
        PaymentConfirmation forged = new PaymentConfirmation(PaymentEventSource.WEBHOOK, PaymentProvider.BAKONG_KHQR,
                payment.getProviderRef(), "TX-forged", payment.getAmount(), "USD", clock.instant(), Map.of(), false);

        assertThat(processor.confirm(forged)).isEqualTo(PaymentOutcome.REJECTED);
        assertThat(payments.payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void confirm_transactionRefOfAnotherPayment_rejected() {
        Payment first = payments.payment(payments.khqrOrder(Map.of(product, 1)));
        Order secondOrder = payments.khqrOrder(Map.of(product, 1));
        Payment second = payments.payment(secondOrder);
        String ref = "TX-" + UUID.randomUUID();
        assertThat(processor.confirm(paid(first, ref, PaymentEventSource.CHECK_JOB))).isEqualTo(PaymentOutcome.APPLIED);

        assertThat(processor.confirm(paid(second, ref, PaymentEventSource.WEBHOOK))).isEqualTo(PaymentOutcome.REJECTED);
        assertThat(payments.payment(secondOrder).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void confirm_afterOrderExpired_lateRefundRequired() {
        Order order = payments.khqrOrder(Map.of(product, 1));
        clock.advance(Duration.ofMinutes(16));
        orderExpiryJob.expireUnpaidOrders();
        Payment cancelled = payments.payment(order);
        assertThat(cancelled.getStatus()).isEqualTo(PaymentStatus.CANCELLED);

        PaymentOutcome outcome = processor.confirm(paid(cancelled, "TX-" + UUID.randomUUID(),
                PaymentEventSource.CHECK_JOB));

        assertThat(outcome).isEqualTo(PaymentOutcome.LATE);
        Payment after = payments.payment(order);
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(after.isRefundRequired()).isTrue();
        assertThat(after.getTransactionRef()).isNotNull();
        assertThat(applicationEvents.stream(PaymentSucceededEvent.class)).isEmpty();
        assertThat(applicationEvents.stream(LatePaymentEvent.class)).singleElement()
                .satisfies(e -> assertThat(e.orderId()).isEqualTo(order.getId()));
        assertThat(events(after)).extracting(PaymentEvent::getOutcome).containsExactly(PaymentOutcome.LATE);
    }

    private PaymentConfirmation paid(Payment payment, String transactionRef, PaymentEventSource source) {
        return new PaymentConfirmation(source, PaymentProvider.BAKONG_KHQR, payment.getProviderRef(), transactionRef,
                payment.getAmount(), payment.getCurrency(), clock.instant(), Map.of("test", true),
                source == PaymentEventSource.WEBHOOK ? Boolean.TRUE : null);
    }

    private List<PaymentEvent> events(Payment payment) {
        return paymentEventRepository.findAllByPaymentIdOrderByIdAsc(payment.getId());
    }
}
