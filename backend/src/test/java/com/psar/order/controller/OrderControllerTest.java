package com.psar.order.controller;

import com.psar.catalog.entity.Product;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.entity.MovementType;
import com.psar.inventory.repository.StockMovementRepository;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.entity.PaymentStatus;
import com.psar.order.repository.OrderRepository;
import com.psar.order.repository.PaymentRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Address;
import com.psar.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTest extends IntegrationTest {

    private static final String URL = "/api/orders";

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    private User customer;
    private Address address;
    private Product rice;

    @BeforeEach
    void setUp() {
        customer = orders.customer();
        address = orders.cityAddress(customer);
        rice = fixtures.product(fixtures.category("My orders"), "Rice 5kg", "6.50", 20, 0);
    }

    @Test
    void myOrders_onlyMine() throws Exception {
        Order mine = orders.order(customer, address, PaymentMethod.CASH_ON_DELIVERY, OrderStatus.CONFIRMED,
                Map.of(rice, 1));
        Order mineKhqr = orders.order(customer, address, PaymentMethod.KHQR, OrderStatus.PENDING_PAYMENT,
                Map.of(rice, 2));
        User other = orders.customer();
        orders.order(other, orders.cityAddress(other), PaymentMethod.CASH_ON_DELIVERY, OrderStatus.CONFIRMED,
                Map.of(rice, 1));

        mockMvc.perform(get(URL).with(TestAuth.as(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                // newest first
                .andExpect(jsonPath("$.content[0].orderNo").value(mineKhqr.getOrderNo()))
                .andExpect(jsonPath("$.content[0].totalAmount").value(14.00))
                .andExpect(jsonPath("$.content[0].id").doesNotExist())
                .andExpect(jsonPath("$.content[1].orderNo").value(mine.getOrderNo()));

        mockMvc.perform(get(URL).param("status", "CONFIRMED").with(TestAuth.as(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].publicId").value(mine.getPublicId().toString()));
    }

    @Test
    void orderDetail_otherCustomer_returns404() throws Exception {
        Order order = orders.order(customer, address, PaymentMethod.CASH_ON_DELIVERY, OrderStatus.CONFIRMED,
                Map.of(rice, 1));
        User other = orders.customer();

        mockMvc.perform(get(URL + "/" + order.getPublicId()).with(TestAuth.as(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post(URL + "/" + order.getPublicId() + "/cancel").with(TestAuth.as(other)))
                .andExpect(status().isNotFound());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void orderDetail_mine_showsItemsMoneyDeliveryAndTimeline() throws Exception {
        Order order = orders.order(customer, address, PaymentMethod.CASH_ON_DELIVERY, OrderStatus.CONFIRMED,
                Map.of(rice, 3));

        mockMvc.perform(get(URL + "/" + order.getPublicId()).with(TestAuth.as(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(order.getOrderNo()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.items[0].productName").value("Rice 5kg"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(6.50))
                .andExpect(jsonPath("$.items[0].lineTotal").value(19.50))
                .andExpect(jsonPath("$.items[0].productId").doesNotExist())
                .andExpect(jsonPath("$.money.subtotal").value(19.50))
                .andExpect(jsonPath("$.money.deliveryFee").value(1.00))
                .andExpect(jsonPath("$.money.totalAmount").value(20.50))
                .andExpect(jsonPath("$.delivery.deliveryType").value("CITY"))
                .andExpect(jsonPath("$.delivery.receiverName").value("Sok Dara"))
                .andExpect(jsonPath("$.delivery.trackingUrl").doesNotExist())
                .andExpect(jsonPath("$.timeline", hasSize(1)))
                .andExpect(jsonPath("$.timeline[0].status").value("CONFIRMED"));
    }

    @Test
    void timeline_hidesStaffIds() throws Exception {
        Order order = orders.order(customer, address, PaymentMethod.CASH_ON_DELIVERY, OrderStatus.CONFIRMED,
                Map.of(rice, 1));
        User staff = orders.driver();
        mockMvc.perform(patch("/api/admin/orders/" + order.getPublicId() + "/status").with(TestAuth.as(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "PROCESSING", "note", "Packing"))))
                .andExpect(status().isOk());

        mockMvc.perform(get(URL + "/" + order.getPublicId()).with(TestAuth.as(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeline", hasSize(2)))
                .andExpect(jsonPath("$.timeline[1].status").value("PROCESSING"))
                .andExpect(jsonPath("$.timeline[1].note").value("Packing"))
                .andExpect(jsonPath("$.timeline[1].changedBy").doesNotExist())
                .andExpect(jsonPath("$.timeline[1].changedById").doesNotExist())
                .andExpect(jsonPath("$.assignedDriver").doesNotExist())
                .andExpect(jsonPath("$.customer").doesNotExist());
    }

    @Test
    void customerCancel_pendingPayment_releasesStockAndCancelsPayment() throws Exception {
        Order order = orders.order(customer, address, PaymentMethod.KHQR, OrderStatus.PENDING_PAYMENT,
                Map.of(rice, 4));
        assertThat(fixtures.quantityOf(rice)).isEqualTo(16);

        mockMvc.perform(post(URL + "/" + order.getPublicId() + "/cancel").with(TestAuth.as(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Wrong address"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.timeline[1].note").value("Cancelled by customer: Wrong address"));

        assertThat(fixtures.quantityOf(rice)).isEqualTo(20);
        assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
        assertThat(stockMovementRepository.findAll()).filteredOn(m -> order.getId().equals(m.getReferenceId())
                        && m.getReason() == MovementReason.RELEASE)
                .singleElement().satisfies(m -> {
                    assertThat(m.getType()).isEqualTo(MovementType.IN);
                    assertThat(m.getQuantity()).isEqualTo(4);
                });
    }

    @Test
    void customerCancel_afterProcessing_returns409() throws Exception {
        Order order = orders.order(customer, address, PaymentMethod.CASH_ON_DELIVERY, OrderStatus.PROCESSING,
                Map.of(rice, 2));

        mockMvc.perform(post(URL + "/" + order.getPublicId() + "/cancel").with(TestAuth.as(customer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PROCESSING);
        assertThat(fixtures.quantityOf(rice)).isEqualTo(18);
    }

    @Test
    void myOrders_asStaff_returns403() throws Exception {
        mockMvc.perform(get(URL).with(TestAuth.as(orders.driver())))
                .andExpect(status().isForbidden());
    }
}
