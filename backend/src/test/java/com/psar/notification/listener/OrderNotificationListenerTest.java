package com.psar.notification.listener;

import com.psar.catalog.entity.Product;
import com.psar.notification.entity.Notification;
import com.psar.notification.entity.NotificationType;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.event.OrderPlacedEvent;
import com.psar.order.job.OrderExpiryJob;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.support.SystemFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Address;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderNotificationListenerTest extends IntegrationTest {

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private SystemFixtures system;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OrderExpiryJob expiryJob;

    private User customer;
    private Address address;
    private Product product;

    @BeforeEach
    void setUp() {
        customer = orders.customer();
        address = orders.cityAddress(customer);
        product = fixtures.product(fixtures.category("Order notify"), "Mango", "2.00", 20, 0);
    }

    @Test
    void codOrderPlaced_notifiesStaff() {
        User staff = system.user(Role.STAFF);
        User manager = system.user(Role.MANAGER);
        User inactiveStaff = system.user(Role.STAFF, false);
        Order cod = orders.order(customer, address, PaymentMethod.CASH_ON_DELIVERY, OrderStatus.CONFIRMED,
                Map.of(product, 1));
        Order khqr = orders.order(customer, address, PaymentMethod.KHQR, OrderStatus.PENDING_PAYMENT,
                Map.of(product, 1));

        publish(new OrderPlacedEvent(cod.getId()));
        publish(new OrderPlacedEvent(khqr.getId()));

        assertThat(newOrder(staff, cod)).singleElement().satisfies(n -> {
            assertThat(n.getTitle()).isEqualTo("New order " + cod.getOrderNo());
            assertThat(n.getMessage()).isEqualTo("Cash on delivery order %s (City), total $3.00."
                    .formatted(cod.getOrderNo()));
            assertThat(n.getLink()).isEqualTo("/admin/orders/" + cod.getPublicId());
        });
        assertThat(newOrder(manager, cod)).hasSize(1);
        assertThat(newOrder(inactiveStaff, cod)).isEmpty();
        assertThat(newOrder(customer, cod)).isEmpty();
        assertThat(newOrder(staff, khqr)).isEmpty();
    }

    @Test
    void statusChange_notifiesCustomer() throws Exception {
        Order order = orders.order(customer, address, PaymentMethod.CASH_ON_DELIVERY, OrderStatus.CONFIRMED,
                Map.of(product, 1));
        User staff = system.user(Role.STAFF);

        mockMvc.perform(patch("/api/admin/orders/" + order.getPublicId() + "/status").with(TestAuth.as(staff))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "PROCESSING"))))
                .andExpect(status().isOk());

        assertThat(system.notifications(customer, NotificationType.ORDER_STATUS_CHANGED)).singleElement()
                .satisfies(n -> {
                    assertThat(n.getTitle()).isEqualTo("Order " + order.getOrderNo() + ": Processing");
                    assertThat(n.getMessage()).isEqualTo("Your order " + order.getOrderNo() + " is now processing.");
                    assertThat(n.getLink()).isEqualTo("/orders/" + order.getPublicId());
                });
        assertThat(system.notifications(staff, NotificationType.ORDER_STATUS_CHANGED)).isEmpty();
    }

    @Test
    void expiredOrder_notifiesCustomerWithReason() {
        Order order = orders.order(customer, address, PaymentMethod.KHQR, OrderStatus.PENDING_PAYMENT,
                Map.of(product, 1));

        clock.advance(Duration.ofMinutes(16));
        expiryJob.expireUnpaidOrders();

        assertThat(system.notifications(customer, NotificationType.ORDER_CANCELLED)).singleElement()
                .satisfies(n -> {
                    assertThat(n.getTitle()).isEqualTo("Order " + order.getOrderNo() + " cancelled");
                    assertThat(n.getMessage())
                            .isEqualTo("Your order " + order.getOrderNo() + " was cancelled: Not paid in time");
                });
        assertThat(system.notifications(customer, NotificationType.ORDER_STATUS_CHANGED)).isEmpty();
    }

    private void publish(Object event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> events.publishEvent(event));
    }

    private List<Notification> newOrder(User user, Order order) {
        return system.notifications(user, NotificationType.NEW_ORDER).stream()
                .filter(n -> n.getTitle().endsWith(order.getOrderNo()))
                .toList();
    }
}
