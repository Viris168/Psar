package com.psar.payment.service;

import com.psar.catalog.entity.Product;
import com.psar.common.exception.NotFoundException;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.PaymentMethod;
import com.psar.payment.dto.response.PaymentView;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentEventSource;
import com.psar.payment.entity.PaymentOutcome;
import com.psar.payment.entity.PaymentProvider;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.gateway.PaymentGateways;
import com.psar.payment.repository.PaymentEventRepository;
import com.psar.payment.repository.PaymentRepository;
import com.psar.payment.service.confirm.PaymentConfirmation;
import com.psar.payment.service.confirm.PaymentProcessor;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.support.PaymentFixtures;
import com.psar.user.entity.User;
import kh.gov.nbc.bakong_khqr.BakongKHQR;
import kh.gov.nbc.bakong_khqr.model.IndividualInfo;
import kh.gov.nbc.bakong_khqr.model.KHQRCurrency;
import kh.gov.nbc.bakong_khqr.model.KHQRData;
import kh.gov.nbc.bakong_khqr.model.KHQRResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Proves the Step 0 payment contract: moved tables, gateway routing, fake Bakong, processor first version. */
class Step0PaymentSchemaTest extends IntegrationTest {

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private PaymentFixtures payments;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentGateways gateways;

    @Autowired
    private PaymentProcessor paymentProcessor;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void createForOrder_khqr_pendingWithGatewayProvider_codWithout() {
        Product product = fixtures.product(fixtures.category("Step0 payments"), "Rice", "2.00", 10, 0);
        User customer = orders.customer();

        Order khqr = orders.order(customer, orders.cityAddress(customer), PaymentMethod.KHQR,
                OrderStatus.PENDING_PAYMENT, Map.of(product, 1));
        Order cod = orders.order(customer, orders.cityAddress(customer), PaymentMethod.CASH_ON_DELIVERY,
                OrderStatus.CONFIRMED, Map.of(product, 1));

        Payment khqrPayment = payments.payment(khqr);
        assertThat(khqrPayment.getProvider()).isEqualTo(PaymentProvider.BAKONG_KHQR);
        assertThat(khqrPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(khqrPayment.getAmount()).isEqualByComparingTo(khqr.getTotalAmount());
        // Postgres keeps microseconds (rounded), the in-memory order has nanoseconds
        assertThat(khqrPayment.getExpiresAt()).isCloseTo(khqr.getExpiresAt(), within(1, ChronoUnit.MILLIS));
        assertThat(payments.payment(cod).getProvider()).isNull();

        PaymentView view = paymentService.findForOrder(khqr.getId()).orElseThrow();
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.method()).isEqualTo("KHQR");
    }

    @Test
    void cancelPending_pendingOnly_paidUntouched() {
        Product product = fixtures.product(fixtures.category("Step0 cancel pay"), "Oil", "1.50", 10, 0);
        Order pending = payments.khqrOrder(Map.of(product, 1));
        Order paid = payments.khqrOrder(Map.of(product, 1));
        Payment paidPayment = payments.payment(paid);
        paidPayment.setStatus(PaymentStatus.SUCCESS);
        paymentRepository.save(paidPayment);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> paymentService.cancelPending(pending.getId()));
        tx.executeWithoutResult(s -> paymentService.cancelPending(paid.getId()));

        assertThat(payments.payment(pending).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payments.payment(paid).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void gateways_khqrToBakong_codNone_pathLookup() {
        assertThat(gateways.forMethod("KHQR")).get()
                .satisfies(g -> assertThat(g.provider()).isEqualTo(PaymentProvider.BAKONG_KHQR));
        assertThat(gateways.forMethod("CASH_ON_DELIVERY")).isEmpty();
        assertThat(gateways.forPath("bakong-khqr").provider()).isEqualTo(PaymentProvider.BAKONG_KHQR);
        assertThatThrownBy(() -> gateways.forPath("paypal")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void fakeBakong_paidOnlyAfterMarked() {
        Product product = fixtures.product(fixtures.category("Step0 fake bakong"), "Salt", "0.50", 10, 0);
        Order order = payments.khqrOrder(Map.of(product, 2));
        String md5 = payments.payment(order).getProviderRef();
        var gateway = gateways.forProvider(PaymentProvider.BAKONG_KHQR);

        assertThat(gateway.checkStatus(md5)).isEmpty();
        String ref = payments.payAtBakong(order);
        assertThat(gateway.checkStatus(md5)).get().satisfies(paid -> {
            assertThat(paid.transactionRef()).isEqualTo(ref);
            assertThat(paid.amount()).isEqualByComparingTo(order.getTotalAmount());
        });
    }

    @Test
    void confirm_step0Version_recordsRejectedEventAndChangesNothing() {
        Product product = fixtures.product(fixtures.category("Step0 confirm"), "Fish sauce", "1.00", 10, 0);
        Order order = payments.khqrOrder(Map.of(product, 1));
        Payment payment = payments.payment(order);

        PaymentOutcome outcome = paymentProcessor.confirm(new PaymentConfirmation(PaymentEventSource.SIMULATED,
                PaymentProvider.BAKONG_KHQR, payment.getProviderRef(), "REF-1", payment.getAmount(), "USD",
                clock.instant(), Map.of("test", true), null));

        assertThat(outcome).isEqualTo(PaymentOutcome.REJECTED);
        assertThat(paymentEventRepository.findAllByProviderRefOrderByIdAsc(payment.getProviderRef()))
                .singleElement().satisfies(e -> assertThat(e.getOutcome()).isEqualTo(PaymentOutcome.REJECTED));
        assertThat(payments.payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    /**
     * The SDK works with OkHttp and commons-validator excluded (pom.xml): it builds and verifies codes offline.
     * A KHQR with an amount ("dynamic") needs an expiration timestamp.
     */
    @Test
    void khqrSdk_withExcludedDependencies_generatesAndVerifiesOffline() {
        IndividualInfo info = new IndividualInfo();
        info.setBakongAccountId("psar_shop@devb");
        info.setMerchantName("Psar Shop");
        info.setMerchantCity("Phnom Penh");
        info.setCurrency(KHQRCurrency.USD);
        info.setAmount(new BigDecimal("12.50").doubleValue());
        info.setBillNumber("ORD-000123");
        // required for a KHQR with an amount (SDK error 45 otherwise): epoch milliseconds
        info.setExpirationTimestamp(clock.instant().plus(Duration.ofMinutes(15)).toEpochMilli());

        KHQRResponse<KHQRData> response = BakongKHQR.generateIndividual(info);

        assertThat(response.getKHQRStatus().getCode()).as(String.valueOf(response.getKHQRStatus())).isZero();
        assertThat(response.getData().getMd5()).hasSize(32);
        assertThat(BakongKHQR.verify(response.getData().getQr()).getData().isValid()).isTrue();
    }
}
