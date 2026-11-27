package com.psar.order.controller;

import com.psar.audit.service.AuditEntity;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.delivery.entity.DeliveryCompany;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.entity.MovementType;
import com.psar.inventory.entity.StockMovement;
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
import com.psar.support.SystemFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Address;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminOrderControllerTest extends IntegrationTest {

    private static final String URL = "/api/admin/orders";

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private SystemFixtures system;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    private User staff;
    private User manager;
    private User customer;
    private Address cityAddress;
    private Product rice;
    private Product oil;

    @BeforeEach
    void setUp() {
        staff = system.user(Role.STAFF);
        manager = system.user(Role.MANAGER);
        customer = orders.customer();
        cityAddress = orders.cityAddress(customer);
        Category category = fixtures.category("Admin orders");
        rice = fixtures.product(category, "Rice 25kg", "30.00", 10, 0);
        oil = fixtures.product(category, "Palm oil", "3.00", 10, 0);
    }

    @Test
    void cityFlow_confirmedToDelivered_historyWithStaffAndDriver() throws Exception {
        Order order = cod(OrderStatus.CONFIRMED);
        User driver = orders.driver();

        changeStatus(order, "PROCESSING", staff).andExpect(status().isOk());
        mockMvc.perform(put(URL + "/" + order.getPublicId() + "/driver").with(TestAuth.as(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("driverPublicId", driver.getPublicId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedDriver.publicId").value(driver.getPublicId().toString()));
        changeStatus(order, "OUT_FOR_DELIVERY", staff).andExpect(status().isOk());
        changeStatus(order, "DELIVERED", staff)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        mockMvc.perform(get(URL + "/" + order.getPublicId()).with(TestAuth.as(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.email").value(customer.getEmail()))
                .andExpect(jsonPath("$.customer.id").doesNotExist())
                .andExpect(jsonPath("$.assignedAt").exists())
                .andExpect(jsonPath("$.timeline", hasSize(4)))
                .andExpect(jsonPath("$.timeline[0].changedBy").doesNotExist())
                .andExpect(jsonPath("$.timeline[3].status").value("DELIVERED"))
                .andExpect(jsonPath("$.timeline[3].changedBy.email").value(staff.getEmail()));

        assertThat(system.auditRows(AuditEntity.ORDER, order.getPublicId().toString())).hasSize(4);
    }

    @Test
    void changeStatus_skippingAStep_returns409() throws Exception {
        Order order = cod(OrderStatus.CONFIRMED);

        changeStatus(order, "DELIVERED", staff)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void changeStatus_toCancelledOrPaid_returns422() throws Exception {
        Order order = cod(OrderStatus.CONFIRMED);

        changeStatus(order, "CANCELLED", manager).andExpect(status().isUnprocessableEntity());
        changeStatus(order, "PAID", manager).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void outForDelivery_withoutDriver_returns422() throws Exception {
        Order order = cod(OrderStatus.PROCESSING);

        changeStatus(order, "OUT_FOR_DELIVERY", staff)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void handedToCourier_withoutTrackingNo_returns422() throws Exception {
        Order order = province(OrderStatus.PROCESSING);

        changeStatus(order, "HANDED_TO_COURIER", staff)
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void handedToCourier_withTrackingNo_showsTrackingLink() throws Exception {
        Order order = province(OrderStatus.PROCESSING);

        mockMvc.perform(put(URL + "/" + order.getPublicId() + "/tracking").with(TestAuth.as(staff))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("trackingNo", " VET 123 "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.trackingNo").value("VET 123"))
                .andExpect(jsonPath("$.delivery.trackingUrl").value("https://track.example/VET+123"));
        changeStatus(order, "HANDED_TO_COURIER", staff)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HANDED_TO_COURIER"));

        mockMvc.perform(get("/api/orders/" + order.getPublicId()).with(TestAuth.as(customer)))
                .andExpect(jsonPath("$.delivery.trackingUrl").value("https://track.example/VET+123"));
    }

    @Test
    void assignDriver_notADriver_returns422() throws Exception {
        Order order = cod(OrderStatus.PROCESSING);

        mockMvc.perform(put(URL + "/" + order.getPublicId() + "/driver").with(TestAuth.as(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("driverPublicId", staff.getPublicId()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.driverPublicId").exists());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getAssignedStaffId()).isNull();
    }

    @Test
    void assignDriver_provinceOrder_returns422() throws Exception {
        Order order = province(OrderStatus.PROCESSING);

        mockMvc.perform(put(URL + "/" + order.getPublicId() + "/driver").with(TestAuth.as(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("driverPublicId", orders.driver().getPublicId()))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cancel_confirmedOrder_releasesStockOncePaymentCancelled() throws Exception {
        Order order = orders.order(customer, cityAddress, PaymentMethod.CASH_ON_DELIVERY, OrderStatus.CONFIRMED,
                Map.of(rice, 2, oil, 3));
        assertThat(fixtures.quantityOf(rice)).isEqualTo(8);
        assertThat(fixtures.quantityOf(oil)).isEqualTo(7);

        cancel(order, manager)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.timeline[1].note").value("Out of stock at the warehouse"));
        cancel(order, manager)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        assertThat(fixtures.quantityOf(rice)).isEqualTo(10);
        assertThat(fixtures.quantityOf(oil)).isEqualTo(10);
        assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
        List<StockMovement> releases = stockMovementRepository.findAll().stream()
                .filter(m -> order.getId().equals(m.getReferenceId()) && m.getReason() == MovementReason.RELEASE)
                .toList();
        assertThat(releases).hasSize(2).allSatisfy(m -> {
            assertThat(m.getType()).isEqualTo(MovementType.IN);
            assertThat(m.getCreatedBy()).isEqualTo(manager.getId());
        });
        assertThat(releases).extracting(StockMovement::getProductId, StockMovement::getQuantity)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(rice.getId(), 2),
                        org.assertj.core.groups.Tuple.tuple(oil.getId(), 3));
    }

    @Test
    void cancel_outForDelivery_returns409() throws Exception {
        Order order = cod(OrderStatus.OUT_FOR_DELIVERY);

        cancel(order, manager).andExpect(status().isConflict());
        assertThat(fixtures.quantityOf(rice)).isEqualTo(9);
    }

    @Test
    void cancel_asStaff_returns403() throws Exception {
        Order order = cod(OrderStatus.CONFIRMED);

        cancel(order, staff).andExpect(status().isForbidden());
    }

    @Test
    void list_filtersByStatusDeliveryTypeAndQuery() throws Exception {
        Order city = cod(OrderStatus.PROCESSING);
        Order prov = province(OrderStatus.PROCESSING);

        mockMvc.perform(get(URL).param("q", city.getOrderNo().toLowerCase()).with(TestAuth.as(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].publicId").value(city.getPublicId().toString()))
                .andExpect(jsonPath("$.content[0].receiverPhone").value("012345678"));

        mockMvc.perform(get(URL).param("q", prov.getOrderNo()).param("deliveryType", "CITY")
                        .with(TestAuth.as(staff)))
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get(URL).param("q", prov.getOrderNo()).param("status", "PROCESSING")
                        .param("deliveryType", "PROVINCE").with(TestAuth.as(staff)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void list_byAssignedDriver_onlyTheirOrders() throws Exception {
        User driver = orders.driver();
        Order assigned = cod(OrderStatus.PROCESSING);
        cod(OrderStatus.PROCESSING);
        mockMvc.perform(put(URL + "/" + assigned.getPublicId() + "/driver").with(TestAuth.as(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("driverPublicId", driver.getPublicId()))))
                .andExpect(status().isOk());

        mockMvc.perform(get(URL).param("assignedTo", driver.getPublicId().toString()).with(TestAuth.as(staff)))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].assignedDriver.email").value(driver.getEmail()));
    }

    @Test
    void list_asCustomer_returns403() throws Exception {
        mockMvc.perform(get(URL).with(TestAuth.as(customer))).andExpect(status().isForbidden());
    }

    private Order cod(OrderStatus status) {
        return orders.order(customer, cityAddress, PaymentMethod.CASH_ON_DELIVERY, status, Map.of(rice, 1));
    }

    private Order province(OrderStatus status) {
        DeliveryCompany company = orders.deliveryCompany("2.00");
        return orders.order(customer, orders.provinceAddress(customer, company), PaymentMethod.KHQR, status,
                Map.of(oil, 1));
    }

    private ResultActions changeStatus(Order order, String next, User as) throws Exception {
        return mockMvc.perform(patch(URL + "/" + order.getPublicId() + "/status").with(TestAuth.as(as))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", next))));
    }

    private ResultActions cancel(Order order, User as) throws Exception {
        return mockMvc.perform(post(URL + "/" + order.getPublicId() + "/cancel").with(TestAuth.as(as))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("reason", "Out of stock at the warehouse"))));
    }
}
