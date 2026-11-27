package com.psar.order.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.order.entity.Cart;
import com.psar.order.entity.CartItem;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.OrderStatusHistory;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.repository.CartRepository;
import com.psar.order.repository.OrderRepository;
import com.psar.order.repository.OrderStatusHistoryRepository;
import com.psar.order.service.delivery.DeliveryQuoteService;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.repository.PaymentRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Address;
import com.psar.user.entity.User;
import com.psar.user.service.AddressLookupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckoutControllerTest extends IntegrationTest {

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
    private CartRepository cartRepository;

    @Autowired
    private AddressLookupService addressLookupService;

    @Autowired
    private DeliveryQuoteService deliveryQuoteService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void checkout_cod_confirmedStockReservedCartCleared() throws Exception {
        Category category = fixtures.category("Checkout COD");
        Product rice = fixtures.product(category, "Rice 5kg", "6.50", 10, 0);
        Product oil = fixtures.product(category, "Oil 1L", "2.25", 5, 0);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(rice, 2, oil, 3));
        BigDecimal fee = fee(customer, address, PaymentMethod.CASH_ON_DELIVERY);

        JsonNode body = body(checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY, "Call on arrival")
                .andExpect(status().isCreated()));

        assertThat(body.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(body.get("orderNo").asText()).matches("ORD-\\d{6}");
        assertThat(body.get("expiresAt").isNull()).isTrue();
        assertThat(body.has("id")).isFalse();
        assertThat(body.get("subtotal").decimalValue()).isEqualByComparingTo("19.75");
        assertThat(body.get("deliveryFee").decimalValue()).isEqualByComparingTo(fee);
        assertThat(body.get("totalAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("19.75").add(fee));
        assertThat(body.get("items")).hasSize(2);

        Order order = orderRepository.findByPublicId(UUID.fromString(body.get("publicId").asText())).orElseThrow();
        assertThat(order.getUserId()).isEqualTo(customer.getId());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getReceiverName()).isEqualTo("Sok Dara");
        assertThat(order.getShippingAddress()).isEqualTo("12, St. 13, Daun Penh, Phnom Penh");
        assertThat(order.getShippingLandmark()).isEqualTo("Near the market");
        assertThat(order.getNote()).isEqualTo("Call on arrival");
        assertThat(historyRepository.findAllByOrderIdOrderByIdAsc(order.getId()))
                .extracting(OrderStatusHistory::getStatus).containsExactly(OrderStatus.CONFIRMED);
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getMethod().name()).isEqualTo("CASH_ON_DELIVERY");
        assertThat(payment.getAmount()).isEqualByComparingTo(order.getTotalAmount());

        assertThat(fixtures.quantityOf(rice)).isEqualTo(8);
        assertThat(fixtures.quantityOf(oil)).isEqualTo(2);
        assertThat(movements(rice, "OUT", "RESERVE", order.getId())).isEqualTo(1);
        assertThat(movements(oil, "OUT", "RESERVE", order.getId())).isEqualTo(1);
        assertThat(cartItems(customer)).isEmpty();
    }

    @Test
    void checkout_khqr_pendingPaymentExpiresIn15Minutes() throws Exception {
        Product product = fixtures.product(fixtures.category("Checkout KHQR"), "Tea", "4.00", 10, 0);
        User customer = orders.customer();
        DeliveryCompanyAndAddress province = provinceAddress(customer);
        orders.cart(customer, Map.of(product, 1));
        BigDecimal fee = fee(customer, province.address(), PaymentMethod.KHQR);
        Instant before = clock.instant();

        JsonNode body = body(checkout(customer, province.address(), PaymentMethod.KHQR, null)
                .andExpect(status().isCreated()));

        assertThat(body.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(Instant.parse(body.get("expiresAt").asText()))
                .isBetween(before.plus(Duration.ofMinutes(15)), clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(body.get("deliveryType").asText()).isEqualTo("PROVINCE");
        assertThat(body.get("deliveryCompanyName").asText()).isEqualTo(province.companyName());
        assertThat(body.get("totalAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("4.00").add(fee));

        Order order = orderRepository.findByPublicId(UUID.fromString(body.get("publicId").asText())).orElseThrow();
        assertThat(order.getCourierBranch()).isEqualTo("Siem Reap branch");
        assertThat(order.getDeliveryCompanyName()).isEqualTo(province.companyName());
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getExpiresAt()).isEqualTo(order.getExpiresAt());
        assertThat(payment.getQrString()).isNull();
    }

    @Test
    void checkout_notEnoughStock_409AndNothingSaved() throws Exception {
        Category category = fixtures.category("Checkout short");
        Product plenty = fixtures.product(category, "Plenty noodles", "0.50", 50, 0);
        Product scarce = fixtures.product(category, "Scarce durian", "9.00", 1, 0);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(plenty, 5, scarce, 2));

        checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value("Not enough stock for Scarce durian"));

        assertThat(jdbc.queryForObject("select count(*) from orders where user_id = ?", Long.class,
                customer.getId())).isZero();
        assertThat(movements(plenty, null, null, null)).isZero();
        assertThat(movements(scarce, null, null, null)).isZero();
        assertThat(fixtures.quantityOf(plenty)).isEqualTo(50);
        assertThat(fixtures.quantityOf(scarce)).isEqualTo(1);
        assertThat(cartItems(customer)).containsExactlyInAnyOrderEntriesOf(
                Map.of(plenty.getId(), 5, scarce.getId(), 2));
    }

    @Test
    void checkout_otherUsersAddress_returns404() throws Exception {
        Product product = fixtures.product(fixtures.category("Checkout 404"), "Soap", "1.00", 5, 0);
        User owner = orders.customer();
        User customer = orders.customer();
        Address ownersAddress = orders.cityAddress(owner);
        orders.cart(customer, Map.of(product, 1));

        checkout(customer, ownersAddress, PaymentMethod.CASH_ON_DELIVERY, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(fixtures.quantityOf(product)).isEqualTo(5);
        assertThat(cartItems(customer)).hasSize(1);
    }

    @Test
    void checkout_emptyCart_returns422() throws Exception {
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);

        checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Your cart is empty"));
    }

    @Test
    void checkout_twiceInARow_secondFindsCartEmpty() throws Exception {
        Product product = fixtures.product(fixtures.category("Checkout double"), "Bread", "1.00", 5, 0);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(product, 1));

        checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY, null).andExpect(status().isCreated());
        checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY, null)
                .andExpect(status().isUnprocessableEntity());

        assertThat(fixtures.quantityOf(product)).isEqualTo(4);
    }

    @Test
    void checkout_productDeactivated_returns422NamingIt() throws Exception {
        Product product = fixtures.product(fixtures.category("Checkout inactive"), "Old candy", "0.30", 5, 0);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(product, 1));
        jdbc.update("update products set is_active = false where id = ?", product.getId());

        checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Old candy")));
        assertThat(fixtures.quantityOf(product)).isEqualTo(5);
    }

    @Test
    void checkout_clientSentPrice_ignored() throws Exception {
        Product product = fixtures.product(fixtures.category("Checkout price"), "Honey", "12.40", 5, 0);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(product, 2));
        BigDecimal fee = fee(customer, address, PaymentMethod.CASH_ON_DELIVERY);

        Map<String, Object> request = new HashMap<>(request(address, PaymentMethod.CASH_ON_DELIVERY, null));
        request.put("unitPrice", "0.01");
        request.put("subtotal", "0.01");
        request.put("deliveryFee", "0");
        request.put("totalAmount", "0.01");
        request.put("items", List.of(Map.of("productPublicId", product.getPublicId(), "unitPrice", "0.01")));

        JsonNode body = body(mockMvc.perform(post("/api/checkout").with(TestAuth.as(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isCreated()));

        assertThat(body.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("12.40");
        assertThat(body.get("subtotal").decimalValue()).isEqualByComparingTo("24.80");
        assertThat(body.get("totalAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("24.80").add(fee));
    }

    @Test
    void checkout_staffUser_returns403() throws Exception {
        mockMvc.perform(post("/api/checkout").with(TestAuth.asRole(1L, "STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("addressPublicId", UUID.randomUUID(), "paymentMethod", "KHQR"))))
                .andExpect(status().isForbidden());
    }

    /** CLAUDE.md launch test: 10 buyers, 3 left → exactly 3 orders succeed. */
    @Test
    void checkout_tenBuyersThreeLeft_exactlyThreeOrders() throws Exception {
        Product product = fixtures.product(fixtures.category("Checkout race"), "Last mangoes", "3.00", 3, 0);
        Map<User, Address> buyers = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            User customer = orders.customer();
            buyers.put(customer, orders.cityAddress(customer));
            orders.cart(customer, Map.of(product, 1));
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(buyers.size());
        List<Future<MvcResult>> results = new ArrayList<>();
        try {
            for (Map.Entry<User, Address> buyer : buyers.entrySet()) {
                Callable<MvcResult> task = () -> {
                    start.await();
                    return checkout(buyer.getKey(), buyer.getValue(), PaymentMethod.CASH_ON_DELIVERY, null)
                            .andReturn();
                };
                results.add(pool.submit(task));
            }
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            for (Future<MvcResult> result : results) {
                MvcResult mvc = result.get(60, TimeUnit.SECONDS);
                statuses.add(mvc.getResponse().getStatus());
                if (mvc.getResponse().getStatus() == 409) {
                    codes.add(objectMapper.readTree(mvc.getResponse().getContentAsString()).get("code").asText());
                }
            }

            assertThat(statuses).filteredOn(s -> s == 201).hasSize(3);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(7);
            assertThat(codes).containsOnly("INSUFFICIENT_STOCK");
        } finally {
            pool.shutdownNow();
        }

        assertThat(fixtures.quantityOf(product)).isZero();
        assertThat(movements(product, "OUT", "RESERVE", null)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(distinct o.id) from orders o join order_items i on i.order_id = o.id "
                + "where i.product_id = ?", Long.class, product.getId())).isEqualTo(3);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private record DeliveryCompanyAndAddress(Address address, String companyName) {
    }

    private DeliveryCompanyAndAddress provinceAddress(User customer) {
        var company = orders.deliveryCompany("2.50");
        return new DeliveryCompanyAndAddress(orders.provinceAddress(customer, company), company.getName());
    }

    /** The fee the quote gives for this address, so tests don't hard-code delivery fees. */
    private BigDecimal fee(User customer, Address address, PaymentMethod method) {
        return deliveryQuoteService.quote(addressLookupService.requireOwned(customer.getId(), address.getPublicId()),
                method).fee();
    }

    private Map<String, Object> request(Address address, PaymentMethod method, String note) {
        Map<String, Object> request = new HashMap<>();
        request.put("addressPublicId", address.getPublicId());
        request.put("paymentMethod", method);
        if (note != null) {
            request.put("note", note);
        }
        return request;
    }

    private ResultActions checkout(User customer, Address address, PaymentMethod method, String note)
            throws Exception {
        return mockMvc.perform(post("/api/checkout").with(TestAuth.as(customer))
                .contentType(MediaType.APPLICATION_JSON).content(json(request(address, method, note))));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Stock movements of a product, optionally filtered by type, reason and reference (order id). */
    private long movements(Product product, String type, String reason, Long referenceId) {
        return jdbc.queryForObject("select count(*) from stock_movements where product_id = ?"
                        + " and (cast(? as varchar) is null or type = ?)"
                        + " and (cast(? as varchar) is null or reason = ?)"
                        + " and (cast(? as bigint) is null or reference_id = ?)", Long.class,
                product.getId(), type, type, reason, reason, referenceId, referenceId);
    }

    private Map<Long, Integer> cartItems(User customer) {
        return cartRepository.findByUserId(customer.getId())
                .map(Cart::getItems)
                .orElse(List.of())
                .stream()
                .collect(java.util.stream.Collectors.toMap(CartItem::getProductId, CartItem::getQuantity));
    }
}
