package com.psar.order.job;

import com.psar.catalog.entity.Product;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.repository.StockMovementRepository;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.OrderStatusHistory;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.entity.PaymentStatus;
import com.psar.order.repository.OrderRepository;
import com.psar.order.repository.OrderStatusHistoryRepository;
import com.psar.order.repository.PaymentRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderExpiryJobTest extends IntegrationTest {

    @Autowired
    private OrderExpiryJob job;

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderStatusHistoryRepository historyRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Test
    void expiryJob_pastExpiry_cancelsAndReleases() {
        Product product = fixtures.product(fixtures.category("Expiry"), "Fish sauce", "1.50", 10, 0);
        User customer = orders.customer();
        Order order = orders.order(customer, orders.cityAddress(customer), PaymentMethod.KHQR,
                OrderStatus.PENDING_PAYMENT, Map.of(product, 3));
        assertThat(fixtures.quantityOf(product)).isEqualTo(7);

        clock.advance(Duration.ofMinutes(16));
        assertThat(job.expireUnpaidOrders()).isGreaterThanOrEqualTo(1);
        job.expireUnpaidOrders(); // running again changes nothing

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(fixtures.quantityOf(product)).isEqualTo(10);
        assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
        assertThat(historyRepository.findAllByOrderIdOrderByIdAsc(order.getId())).last().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(row.getChangedBy()).isNull();
            assertThat(row.getNote()).isEqualTo("Not paid in time");
        });
        assertThat(stockMovementRepository.findAll()).filteredOn(m -> order.getId().equals(m.getReferenceId())
                && m.getReason() == MovementReason.RELEASE).hasSize(1);
    }

    @Test
    void expiryJob_notExpired_untouched() {
        Product product = fixtures.product(fixtures.category("Not expired"), "Noodles", "0.80", 10, 0);
        User customer = orders.customer();
        Order order = orders.order(customer, orders.cityAddress(customer), PaymentMethod.KHQR,
                OrderStatus.PENDING_PAYMENT, Map.of(product, 2));

        clock.advance(Duration.ofMinutes(14));
        job.expireUnpaidOrders();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(fixtures.quantityOf(product)).isEqualTo(8);
        assertThat(historyRepository.findAllByOrderIdOrderByIdAsc(order.getId()))
                .extracting(OrderStatusHistory::getStatus).containsExactly(OrderStatus.PENDING_PAYMENT);
    }
}
