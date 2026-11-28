package com.psar.order.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderItem;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.job.OrderExpiryJob;
import com.psar.order.repository.OrderRepository;
import com.psar.order.service.pricing.PricingCalculator;
import com.psar.order.service.pricing.PricingInput;
import com.psar.promotion.dto.request.UsedPromotion;
import com.psar.promotion.entity.OrderPromotion;
import com.psar.promotion.entity.Promotion;
import com.psar.promotion.service.PromotionUsageService;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.support.PromotionFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Address;
import com.psar.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Coupons and promotions in the quote, checkout, order detail, cancel and expiry.
 * <p>
 * Only product-targeted automatic promotions and coupons are created: the database is shared by all tests, so
 * an automatic ALL / ORDER promotion would change other tests' totals.
 * <p>
 * Tests marked "needs pricing" assert the discounts PricingCalculator computes (task6.md "Pricing rules"); they
 * are skipped while PricingCalculator is still the Step 0 version that ignores offers.
 */
class CheckoutPromotionControllerTest extends IntegrationTest {

    @Autowired
    private CatalogFixtures catalog;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private PromotionFixtures promotions;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PromotionUsageService usageService;

    @Autowired
    private OrderExpiryJob expiryJob;

    @Autowired
    private PricingCalculator pricingCalculator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    // ---- coupon that doesn't apply ------------------------------------------------------------------

    @Test
    void quote_couponUnknown_200WithReason() throws Exception {
        Product product = catalog.product(catalog.category("Quote unknown coupon"), "Quote tea", "4.00", 10, 0);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(product, 2));

        JsonNode body = body(quote(customer, address, "  nosuch-code ").andExpect(status().isOk()));

        assertThat(body.get("coupon").get("code").asText()).isEqualTo("NOSUCH-CODE");
        assertThat(body.get("coupon").get("applied").asBoolean()).isFalse();
        assertThat(body.get("coupon").get("reason").asText()).isEqualTo("Coupon NOSUCH-CODE is not valid");
        assertThat(body.get("orderDiscount").decimalValue()).isEqualByComparingTo("0");
        assertThat(body.get("total").decimalValue())
                .isEqualByComparingTo(new BigDecimal("8.00").add(body.get("deliveryFee").decimalValue()));
        assertThat(body.get("promotions")).isEmpty();
    }

    @Test
    void quote_noCoupon_couponSectionNull() throws Exception {
        Product product = catalog.product(catalog.category("Quote no coupon"), "Quote soap", "1.00", 10, 0);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(product, 1));

        quote(customer, address, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon").doesNotExist())
                .andExpect(jsonPath("$.lines[0].freeQuantity").value(0))
                .andExpect(jsonPath("$.lines[0].promotionName").doesNotExist());
    }

    @Test
    void checkout_couponUnknown_422NothingSaved() throws Exception {
        Product product = catalog.product(catalog.category("Checkout unknown coupon"), "Unknown rice", "6.00", 10, 0);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(product, 1));

        checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY, "nope-123")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUPON_INVALID"))
                .andExpect(jsonPath("$.message").value("Coupon NOPE-123 is not valid"));

        assertThat(orderCount(customer)).isZero();
        assertThat(catalog.quantityOf(product)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where product_id = ?", Long.class,
                product.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from cart_items ci join carts c on c.id = ci.cart_id "
                + "where c.user_id = ?", Long.class, customer.getId())).isEqualTo(1);
    }

    @Test
    void checkout_perUserLimit_secondOrder422() throws Exception {
        Product product = catalog.product(catalog.category("Per-user coupon"), "Per-user milk", "10.00", 10, 0);
        String code = promotions.uniqueCode("ONCE");
        Promotion coupon = promotions.coupon(code, "1.00", null, null, 1);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orderThatUsed(customer, address, product, coupon, PaymentMethod.CASH_ON_DELIVERY);
        orders.cart(customer, Map.of(product, 1));

        checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY, code.toLowerCase())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUPON_INVALID"))
                .andExpect(jsonPath("$.message").value("You have already used coupon " + code));

        assertThat(orderCount(customer)).isEqualTo(1);
        assertThat(promotions.usedCount(coupon)).isEqualTo(1);
    }

    // ---- release -----------------------------------------------------------------------------------

    @Test
    void cancel_releasesUsage_couponUsableAgain() throws Exception {
        Product product = catalog.product(catalog.category("Cancel coupon"), "Cancel beans", "10.00", 10, 0);
        String code = promotions.uniqueCode("AGAIN");
        Promotion coupon = promotions.coupon(code, "1.00", null, 1, 1);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        Order order = orderThatUsed(customer, address, product, coupon, PaymentMethod.CASH_ON_DELIVERY);
        orders.cart(customer, Map.of(product, 1));
        assertThat(quoteCoupon(customer, address, code).get("reason").asText())
                .isEqualTo("Coupon " + code + " has been fully used");

        mockMvc.perform(post("/api/orders/{id}/cancel", order.getPublicId()).with(TestAuth.as(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.promotions[0].couponCode").value(code))
                .andExpect(jsonPath("$.promotions[0].released").value(true));

        assertThat(promotions.usedCount(coupon)).isZero();
        assertThat(promotions.orderPromotions(order)).singleElement()
                .satisfies(row -> assertThat(row.isReleased()).isTrue());
        String reason = quoteCoupon(customer, address, code).path("reason").asText(null);
        assertThat(String.valueOf(reason)).as("coupon counts as unused again")
                .doesNotContain("fully used").doesNotContain("already used");
    }

    @Test
    void expiry_releasesUsage() {
        Product product = catalog.product(catalog.category("Expiry coupon"), "Expiry beans", "10.00", 10, 0);
        String code = promotions.uniqueCode("LATE");
        Promotion coupon = promotions.coupon(code, "1.00", null, 5, null);
        User customer = orders.customer();
        Order order = orderThatUsed(customer, orders.cityAddress(customer), product, coupon, PaymentMethod.KHQR);
        assertThat(promotions.usedCount(coupon)).isEqualTo(1);

        clock.advance(Duration.ofMinutes(16));
        expiryJob.expireUnpaidOrders();
        expiryJob.expireUnpaidOrders(); // again: given back only once

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(promotions.usedCount(coupon)).isZero();
        assertThat(promotions.orderPromotions(order)).singleElement()
                .satisfies(row -> assertThat(row.isReleased()).isTrue());
    }

    @Test
    void orderDetail_customerAndAdmin_listPromotions() throws Exception {
        Product product = catalog.product(catalog.category("Detail coupon"), "Detail beans", "10.00", 10, 0);
        Promotion coupon = promotions.coupon(promotions.uniqueCode("SHOW"), "1.00", null, null, null);
        User customer = orders.customer();
        Order order = orderThatUsed(customer, orders.cityAddress(customer), product, coupon,
                PaymentMethod.CASH_ON_DELIVERY);

        mockMvc.perform(get("/api/orders/{id}", order.getPublicId()).with(TestAuth.as(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotions.length()").value(1))
                .andExpect(jsonPath("$.promotions[0].promotionName").value(coupon.getName()))
                .andExpect(jsonPath("$.promotions[0].couponCode").value(coupon.getCouponCode()))
                .andExpect(jsonPath("$.promotions[0].level").value("ORDER"))
                .andExpect(jsonPath("$.promotions[0].discountAmount").value(1.00))
                .andExpect(jsonPath("$.promotions[0].released").value(false))
                .andExpect(jsonPath("$.promotions[0].promotionId").doesNotExist());
        mockMvc.perform(get("/api/admin/orders/{id}", order.getPublicId()).with(TestAuth.as(catalog.manager())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotions.length()").value(1))
                .andExpect(jsonPath("$.promotions[0].promotionName").value(coupon.getName()));
    }

    // ---- with real pricing -------------------------------------------------------------------------

    /** Needs pricing. 10% off a $10 product, 3 units: $3 off the line, snapshotted on the order. */
    @Test
    void checkout_automaticPercentage_discountSnapshotted() throws Exception {
        assumePricingAppliesOffers();
        Product product = catalog.product(catalog.category("Auto percent"), "Auto coffee", "10.00", 10, 0);
        Promotion tenPercent = promotions.percentageOnProducts("10", product);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(product, 3));

        JsonNode body = body(checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY, null)
                .andExpect(status().isCreated()));

        BigDecimal fee = body.get("deliveryFee").decimalValue();
        assertThat(body.get("subtotal").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(body.get("productDiscount").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(body.get("totalAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("27.00").add(fee));
        Order order = order(body);
        assertThat(order.getProductDiscount()).isEqualByComparingTo("3.00");
        assertThat(order.getOrderDiscount()).isEqualByComparingTo("0.00");
        OrderItem item = items(order).getFirst();
        assertThat(item.getDiscountAmount()).isEqualByComparingTo("3.00");
        assertThat(item.getLineTotal()).isEqualByComparingTo("30.00");
        assertThat(promotions.orderPromotions(order)).singleElement().satisfies(row -> {
            assertThat(row.getPromotionId()).isEqualTo(tenPercent.getId());
            assertThat(row.getPromotionName()).isEqualTo(tenPercent.getName());
            assertThat(row.getLevel().name()).isEqualTo("ITEM");
            assertThat(row.getDiscountAmount()).isEqualByComparingTo("3.00");
            assertThat(row.getCouponCode()).isNull();
        });
        assertThat(promotions.usedCount(tenPercent)).isEqualTo(1);
    }

    /** Needs pricing. $5 off over $20 on a $25 order. */
    @Test
    void checkout_coupon_orderDiscountAndUsageCounted() throws Exception {
        assumePricingAppliesOffers();
        Product product = catalog.product(catalog.category("Coupon order"), "Coupon rice", "12.50", 10, 0);
        String code = promotions.uniqueCode("FIVE");
        Promotion coupon = promotions.coupon(code, "5.00", "20.00", 10, 1);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(product, 2));

        JsonNode body = body(checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY, code.toLowerCase())
                .andExpect(status().isCreated()));

        BigDecimal fee = body.get("deliveryFee").decimalValue();
        assertThat(body.get("orderDiscount").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(body.get("totalAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("20.00").add(fee));
        Order order = order(body);
        assertThat(order.getOrderDiscount()).isEqualByComparingTo("5.00");
        assertThat(promotions.usedCount(coupon)).isEqualTo(1);
        assertThat(promotions.orderPromotions(order)).singleElement().satisfies(row -> {
            assertThat(row.getCouponCode()).isEqualTo(code);
            assertThat(row.getLevel().name()).isEqualTo("ORDER");
            assertThat(row.getDiscountAmount()).isEqualByComparingTo("5.00");
            assertThat(row.getUserId()).isEqualTo(customer.getId());
        });
        mockMvc.perform(get("/api/orders/{id}", order.getPublicId()).with(TestAuth.as(customer)))
                .andExpect(jsonPath("$.promotions[0].couponCode").value(code))
                .andExpect(jsonPath("$.money.orderDiscount").value(5.00));
    }

    /** Needs pricing. Below the coupon's minimum: the quote explains, checkout says 422. */
    @Test
    void checkout_couponBelowMinimum_422() throws Exception {
        assumePricingAppliesOffers();
        Product product = catalog.product(catalog.category("Coupon minimum"), "Minimum rice", "5.00", 10, 0);
        String code = promotions.uniqueCode("MIN");
        promotions.coupon(code, "5.00", "20.00", null, null);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        orders.cart(customer, Map.of(product, 2));

        assertThat(quoteCoupon(customer, address, code).get("reason").asText())
                .isEqualTo("Coupon " + code + " needs an order of at least $20.00");
        checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY, code)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUPON_INVALID"));
        assertThat(orderCount(customer)).isZero();
    }

    /**
     * Needs pricing. Coupon with usage limit 1, ten buyers at once: exactly one order uses it. The others fail
     * with 409 PROMOTION_EXHAUSTED (they priced the coupon before the winner committed, then lost the atomic
     * claim) or, if they started after the winner committed, 422 COUPON_INVALID ("fully used"); either way the
     * whole checkout rolls back, so their stock is untouched.
     */
    @Test
    void checkout_couponWithLimitOne_tenBuyersConcurrently_exactlyOneUses() throws Exception {
        assumePricingAppliesOffers();
        Product product = catalog.product(catalog.category("Coupon race"), "Race mangoes", "3.00", 20, 0);
        String code = promotions.uniqueCode("RACE");
        Promotion coupon = promotions.coupon(code, "1.00", null, 1, null);
        Map<User, Address> buyers = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            User customer = orders.customer();
            buyers.put(customer, orders.cityAddress(customer));
            orders.cart(customer, Map.of(product, 2));
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(buyers.size());
        List<Integer> statuses = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        try {
            List<Future<MvcResult>> results = new ArrayList<>();
            for (Map.Entry<User, Address> buyer : buyers.entrySet()) {
                Callable<MvcResult> task = () -> {
                    start.await();
                    return checkout(buyer.getKey(), buyer.getValue(), PaymentMethod.CASH_ON_DELIVERY, code)
                            .andReturn();
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            for (Future<MvcResult> result : results) {
                MvcResult mvc = result.get(60, TimeUnit.SECONDS);
                statuses.add(mvc.getResponse().getStatus());
                if (mvc.getResponse().getStatus() != 201) {
                    codes.add(objectMapper.readTree(mvc.getResponse().getContentAsString()).get("code").asText());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409 || s == 422).hasSize(9);
        assertThat(codes).hasSize(9).allMatch(c -> c.equals("PROMOTION_EXHAUSTED") || c.equals("COUPON_INVALID"));
        assertThat(promotions.usedCount(coupon)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_promotions where promotion_id = ?", Long.class,
                coupon.getId())).isEqualTo(1);
        assertThat(catalog.quantityOf(product)).isEqualTo(18);
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where product_id = ?", Long.class,
                product.getId())).isEqualTo(1);
    }

    /** Needs pricing. An item percentage, a buy 2 get 1 and a coupon: checkout charges exactly the quote. */
    @Test
    void quote_matchesCheckoutTotal_withPromotions() throws Exception {
        assumePricingAppliesOffers();
        Category category = catalog.category("Quote equals checkout");
        Product coffee = catalog.product(category, "Match coffee", "7.35", 10, 0);
        Product noodles = catalog.product(category, "Match noodles", "1.20", 10, 0);
        Promotion percent = promotions.percentageOnProducts("15", coffee);
        Promotion bxgy = promotions.buyXGetY(2, 1, noodles);
        String code = promotions.uniqueCode("MATCH");
        Promotion coupon = promotions.coupon(code, "2.00", "10.00", null, null);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);
        Map<Product, Integer> lines = new LinkedHashMap<>();
        lines.put(coffee, 3);
        lines.put(noodles, 3);
        orders.cart(customer, lines);

        JsonNode quote = body(quote(customer, address, code).andExpect(status().isOk()));
        assertThat(quote.get("coupon").get("applied").asBoolean()).isTrue();
        assertThat(quote.get("promotions")).extracting(p -> p.get("name").asText())
                .containsExactlyInAnyOrder(percent.getName(), bxgy.getName(), coupon.getName());
        JsonNode noodleLine = quote.get("lines").get(1);
        assertThat(noodleLine.get("freeQuantity").asInt()).isEqualTo(1);
        assertThat(noodleLine.get("promotionName").asText()).isEqualTo(bxgy.getName());

        JsonNode placed = body(checkout(customer, address, PaymentMethod.CASH_ON_DELIVERY, code)
                .andExpect(status().isCreated()));

        for (String field : List.of("subtotal", "productDiscount", "orderDiscount", "rewardDiscount",
                "deliveryFee")) {
            assertThat(placed.get(field).decimalValue()).as(field)
                    .isEqualByComparingTo(quote.get(field).decimalValue());
        }
        assertThat(placed.get("totalAmount").decimalValue()).isEqualByComparingTo(quote.get("total").decimalValue());
        // 3 × 7.35 = 22.05, 15% = 3.31 (HALF_UP); noodles 3 × 1.20 = 3.60, 1 free = 1.20; coupon 2.00
        assertThat(placed.get("productDiscount").decimalValue()).isEqualByComparingTo("4.51");
        assertThat(placed.get("orderDiscount").decimalValue()).isEqualByComparingTo("2.00");
        Order order = order(placed);
        assertThat(items(order)).filteredOn(i -> i.getProductId().equals(noodles.getId())).singleElement()
                .satisfies(i -> {
                    assertThat(i.getFreeQuantity()).isEqualTo(1);
                    assertThat(i.getDiscountAmount()).isEqualByComparingTo("1.20");
                });
        assertThat(catalog.quantityOf(noodles)).isEqualTo(7); // free units leave the shelf too
        assertThat(promotions.orderPromotions(order)).extracting(OrderPromotion::getPromotionId)
                .containsExactlyInAnyOrder(percent.getId(), bxgy.getId(), coupon.getId());
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Skips the test while PricingCalculator is the Step 0 version (offers ignored). */
    private void assumePricingAppliesOffers() {
        PricingInput.Offer probe = new PricingInput.Offer(1L, "probe", PricingInput.OfferType.PERCENTAGE,
                PricingInput.OfferLevel.ITEM, BigDecimal.TEN, null, null, null, null, Set.of(1L), false,
                Instant.now());
        BigDecimal discount = pricingCalculator.price(new PricingInput(
                List.of(new PricingInput.Line(1L, 1, BigDecimal.TEN)), BigDecimal.ZERO, List.of(probe)))
                .productDiscount();
        assumeTrue(discount.signum() > 0, "PricingCalculator doesn't apply offers yet (Agent B)");
    }

    /** An order for this customer (checkout-like fixture) that used {@code promotion}, claimed as checkout does. */
    private Order orderThatUsed(User customer, Address address, Product product, Promotion promotion,
                                PaymentMethod method) {
        OrderStatus status = method == PaymentMethod.KHQR ? OrderStatus.PENDING_PAYMENT : OrderStatus.CONFIRMED;
        Order order = orders.order(customer, address, method, status, Map.of(product, 1));
        String level = promotion.getApplyTo().isItemLevel() ? "ITEM" : "ORDER";
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> usageService.claim(order.getId(),
                customer.getId(), List.of(new UsedPromotion(promotion.getId(), level, new BigDecimal("1.00")))));
        return order;
    }

    private ResultActions quote(User customer, Address address, String couponCode) throws Exception {
        var request = get("/api/checkout/quote").with(TestAuth.as(customer))
                .param("addressPublicId", address.getPublicId().toString())
                .param("paymentMethod", "CASH_ON_DELIVERY");
        if (couponCode != null) {
            request.param("couponCode", couponCode);
        }
        return mockMvc.perform(request);
    }

    private JsonNode quoteCoupon(User customer, Address address, String code) throws Exception {
        return body(quote(customer, address, code).andExpect(status().isOk())).get("coupon");
    }

    private ResultActions checkout(User customer, Address address, PaymentMethod method, String couponCode)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("addressPublicId", address.getPublicId());
        request.put("paymentMethod", method);
        if (couponCode != null) {
            request.put("couponCode", couponCode);
        }
        return mockMvc.perform(post("/api/checkout").with(TestAuth.as(customer))
                .contentType(MediaType.APPLICATION_JSON).content(json(request)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Order order(JsonNode checkoutBody) {
        return orderRepository.findByPublicId(UUID.fromString(checkoutBody.get("publicId").asText())).orElseThrow();
    }

    private List<OrderItem> items(Order order) {
        return new TransactionTemplate(transactionManager).execute(tx ->
                List.copyOf(orderRepository.findById(order.getId()).orElseThrow().getItems()));
    }

    private long orderCount(User customer) {
        return jdbc.queryForObject("select count(*) from orders where user_id = ?", Long.class, customer.getId());
    }
}
