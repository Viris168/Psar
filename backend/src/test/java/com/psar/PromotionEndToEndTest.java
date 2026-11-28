package com.psar;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import com.psar.user.entity.UserProfile;
import com.psar.user.repository.UserProfileRepository;
import com.psar.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6 end to end, with real JWTs and only the API (task6.md "Merge order" step 4):
 * a manager creates an automatic 10 % on a top-level category, buy 2 get 1 on a product and the coupon WELCOME5
 * ($5 off from $20, once per customer) and broadcasts it → the customer's quote shows all three → KHQR checkout →
 * the order's snapshot and totals match the quote → the same customer can't use WELCOME5 again (quote explains,
 * checkout 422) → the order is cancelled → the coupon works again; then free delivery from $30.
 * <p>
 * Hand-calculated cart (CITY at the shop, fee $1.00): rice 3 × $6.00 = $18.00, 10 % → −$1.80;
 * oil 3 × $4.00 = $12.00, 1 free → −$4.00. Subtotal $30.00, item discounts $5.80, after items $24.20 ≥ $20
 * → WELCOME5 −$5.00. Total 30.00 − 5.80 − 5.00 + 1.00 = <b>$20.20</b>.
 */
class PromotionEndToEndTest extends IntegrationTest {

    private static final String PASSWORD = "Password123!";
    private static final String COUPON = "WELCOME5";

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void promotions_quoteCheckoutCouponLimitCancelAndFreeDelivery() throws Exception {
        Category grains = fixtures.category("E2E grains " + shortId());
        Category riceCategory = fixtures.category("E2E rice " + shortId(), grains);
        Product rice = fixtures.product(riceCategory, "Jasmine rice", "6.00", 50, 0);
        Product oil = fixtures.product(fixtures.category("E2E oil " + shortId()), "Cooking oil", "4.00", 50, 0);
        String manager = loginAs(Role.MANAGER);
        String customer = registerCustomer();
        String address = addCityAddress(customer);

        // ---- the manager sets up three promotions and announces the coupon ---------------------------------
        String tenPercent = "Grains 10% " + shortId();
        createPromotion(manager, tenPercent, "PERCENTAGE", "CATEGORIES", b -> {
            b.put("discountValue", "10");
            b.put("categorySlugs", List.of(grains.getSlug()));
        });
        String buy2get1 = "Oil buy 2 get 1 " + shortId();
        createPromotion(manager, buy2get1, "BUY_X_GET_Y", "PRODUCTS", b -> {
            b.put("buyQuantity", 2);
            b.put("getQuantity", 1);
            b.put("productIds", List.of(oil.getPublicId()));
        });
        String welcome = "Welcome $5 " + shortId();
        JsonNode coupon = createPromotion(manager, welcome, "FIXED_AMOUNT", "ORDER", b -> {
            b.put("discountValue", "5.00");
            b.put("minOrderAmount", "20.00");
            b.put("couponCode", "welcome5");
            b.put("perUserLimit", 1);
        });
        String couponId = coupon.get("publicId").asText();
        assertThat(coupon.get("couponCode").asText()).isEqualTo(COUPON);

        send(post("/api/admin/promotions/" + couponId + "/broadcast"), customer, null)
                .andExpect(status().isForbidden());
        send(post("/api/admin/promotions/" + couponId + "/broadcast"), manager, null).andExpect(status().isOk());
        send(post("/api/admin/promotions/" + couponId + "/broadcast"), manager, null)
                .andExpect(status().isConflict());
        assertThat(notificationTitles(customer, "PROMOTION")).containsOnlyOnce(welcome);

        // the storefront lists the automatic two, never the coupon
        JsonNode storefront = body(send(get("/api/public/promotions").param("size", "100"), null, null)
                .andExpect(status().isOk()));
        assertThat(names(storefront.get("content"))).contains(tenPercent, buy2get1).doesNotContain(welcome);
        assertThat(storefront.toString()).doesNotContain(COUPON);

        // ---- quote, then KHQR checkout: the same numbers ---------------------------------------------------
        addToCart(customer, rice, 3);
        addToCart(customer, oil, 3);
        JsonNode quote = quote(customer, address, "welcome5");
        assertMoney(quote, "subtotal", "30.00");
        assertMoney(quote, "productDiscount", "5.80");
        assertMoney(quote, "orderDiscount", "5.00");
        assertMoney(quote, "deliveryFee", "1.00");
        assertMoney(quote, "total", "20.20");
        assertThat(quote.at("/coupon/applied").asBoolean()).isTrue();
        assertThat(names(quote.get("promotions"))).containsExactlyInAnyOrder(tenPercent, buy2get1, welcome);
        JsonNode oilLine = line(quote.get("lines"), "Cooking oil");
        assertThat(oilLine.get("quantity").asInt()).isEqualTo(3);
        assertThat(oilLine.get("freeQuantity").asInt()).isOne();
        assertThat(oilLine.get("promotionName").asText()).isEqualTo(buy2get1);

        JsonNode order = body(checkout(customer, address, "welcome5").andExpect(status().isCreated()));
        String orderId = order.get("publicId").asText();
        assertMoney(order, "totalAmount", "20.20");
        assertThat(usedCount(manager, couponId)).isOne();

        JsonNode detail = body(send(get("/api/orders/" + orderId), customer, null).andExpect(status().isOk()));
        assertMoney(detail.get("money"), "subtotal", "30.00");
        assertMoney(detail.get("money"), "productDiscount", "5.80");
        assertMoney(detail.get("money"), "orderDiscount", "5.00");
        assertMoney(detail.get("money"), "totalAmount", "20.20");
        assertThat(detail.get("promotions")).hasSize(3);
        JsonNode couponRow = row(detail.get("promotions"), welcome);
        assertThat(couponRow.get("couponCode").asText()).isEqualTo(COUPON);
        assertThat(couponRow.get("level").asText()).isEqualTo("ORDER");
        assertMoney(couponRow, "discountAmount", "5.00");
        assertMoney(row(detail.get("promotions"), buy2get1), "discountAmount", "4.00");
        assertThat(line(detail.get("items"), "Cooking oil").get("freeQuantity").asInt()).isOne();

        // ---- the same customer can't use WELCOME5 again: the quote explains, checkout refuses ----------------
        addToCart(customer, rice, 3);
        addToCart(customer, oil, 3);
        JsonNode again = quote(customer, address, COUPON);
        assertThat(again.at("/coupon/applied").asBoolean()).isFalse();
        assertThat(again.at("/coupon/reason").asText()).isNotBlank();
        assertMoney(again, "orderDiscount", "0.00");
        assertMoney(again, "total", "25.20");
        checkout(customer, address, COUPON).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUPON_INVALID"));
        assertThat(orderCount(customer)).isOne();
        assertThat(usedCount(manager, couponId)).isOne();

        // ---- the first order is cancelled: the coupon is given back and works again ------------------------
        send(post("/api/orders/" + orderId + "/cancel"), customer, Map.of("reason", "Changed my mind"))
                .andExpect(status().isOk());
        assertThat(usedCount(manager, couponId)).isZero();
        JsonNode cancelled = body(send(get("/api/orders/" + orderId), customer, null));
        assertThat(row(cancelled.get("promotions"), welcome).get("released").asBoolean()).isTrue();

        assertThat(quote(customer, address, COUPON).at("/coupon/applied").asBoolean()).isTrue();
        JsonNode second = body(checkout(customer, address, COUPON).andExpect(status().isCreated()));
        assertMoney(second, "totalAmount", "20.20");
        assertThat(usedCount(manager, couponId)).isOne();

        // ---- free delivery from $30 (on the subtotal after item discounts) ---------------------------------
        String freeDelivery = "Free delivery " + shortId();
        createPromotion(manager, freeDelivery, "FREE_DELIVERY", "ORDER", b -> b.put("minOrderAmount", "30.00"));
        String other = registerCustomer();
        String otherAddress = addCityAddress(other);

        addToCart(other, rice, 3); // 18.00 − 1.80 = 16.20: below $30, fee stays
        JsonNode below = quote(other, otherAddress, null);
        assertMoney(below, "deliveryFee", "1.00");
        assertMoney(below, "total", "17.20");
        assertThat(names(below.get("promotions"))).doesNotContain(freeDelivery);

        addToCart(other, rice, 3); // now 6: 36.00 − 3.60 = 32.40 ≥ $30
        JsonNode over = quote(other, otherAddress, null);
        assertMoney(over, "deliveryFee", "0.00");
        assertMoney(over, "total", "32.40");
        assertMoney(row(over.get("promotions"), freeDelivery), "discountAmount", "1.00");
        JsonNode freeOrder = body(checkout(other, otherAddress, null).andExpect(status().isCreated()));
        assertMoney(freeOrder, "deliveryFee", "0.00");
        assertMoney(freeOrder, "totalAmount", "32.40");
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private JsonNode createPromotion(String token, String name, String type, String applyTo,
                                     Consumer<Map<String, Object>> customize) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", type);
        body.put("applyTo", applyTo);
        body.put("startDate", clock.instant().minus(Duration.ofHours(1)).toString());
        body.put("endDate", clock.instant().plus(Duration.ofDays(30)).toString());
        customize.accept(body);
        return body(send(post("/api/admin/promotions"), token, body).andExpect(status().isCreated()));
    }

    private int usedCount(String manager, String promotionId) throws Exception {
        return body(send(get("/api/admin/promotions/" + promotionId), manager, null).andExpect(status().isOk()))
                .get("usedCount").asInt();
    }

    private void addToCart(String token, Product product, int quantity) throws Exception {
        send(post("/api/cart/items"), token, Map.of("productPublicId", product.getPublicId(), "quantity", quantity))
                .andExpect(status().isOk());
    }

    private JsonNode quote(String token, String address, String couponCode) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/checkout/quote")
                .param("addressPublicId", address).param("paymentMethod", "KHQR");
        if (couponCode != null) {
            request.param("couponCode", couponCode);
        }
        return body(send(request, token, null).andExpect(status().isOk()));
    }

    private ResultActions checkout(String token, String address, String couponCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addressPublicId", address);
        body.put("paymentMethod", "KHQR");
        if (couponCode != null) {
            body.put("couponCode", couponCode);
        }
        return send(post("/api/checkout"), token, body);
    }

    private int orderCount(String token) throws Exception {
        return body(send(get("/api/orders").param("size", "100"), token, null).andExpect(status().isOk()))
                .get("content").size();
    }

    private List<String> notificationTitles(String token, String type) throws Exception {
        JsonNode page = body(send(get("/api/notifications").param("size", "100"), token, null)
                .andExpect(status().isOk()));
        List<String> titles = new ArrayList<>();
        page.get("content").forEach(n -> {
            if (n.get("type").asText().equals(type)) {
                titles.add(n.get("title").asText());
            }
        });
        return titles;
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(node -> names.add(node.has("name") ? node.get("name").asText()
                : node.get("promotionName").asText()));
        return names;
    }

    private static JsonNode row(JsonNode array, String name) {
        for (JsonNode node : array) {
            if (name.equals(node.has("name") ? node.get("name").asText() : node.get("promotionName").asText())) {
                return node;
            }
        }
        throw new AssertionError("no promotion " + name + " in " + array);
    }

    private static JsonNode line(JsonNode lines, String productName) {
        for (JsonNode node : lines) {
            String name = node.has("productName") ? node.get("productName").asText() : node.get("name").asText();
            if (name.equals(productName)) {
                return node;
            }
        }
        throw new AssertionError("no line " + productName + " in " + lines);
    }

    private static void assertMoney(JsonNode node, String field, String expected) {
        assertThat(node.get(field)).as(field).isNotNull();
        assertThat(node.get(field).decimalValue()).as(field).isEqualByComparingTo(new BigDecimal(expected));
    }

    private String registerCustomer() throws Exception {
        String email = "e2e-promo-" + shortId() + "@test.local";
        send(post("/api/auth/register"), null, Map.of("email", email, "password", PASSWORD, "fullName", "Keo Bopha"))
                .andExpect(status().isCreated());
        return login(email);
    }

    /** Staff accounts can't self-register, so create one directly and log in through the API. */
    private String loginAs(Role role) throws Exception {
        String email = "e2e-" + role.name().toLowerCase() + "-" + shortId() + "@test.local";
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user = userRepository.save(user);
        UserProfile profile = new UserProfile();
        profile.setUser(user);
        profile.setFullName("E2E " + role.name());
        userProfileRepository.save(profile);
        return login(email);
    }

    private String login(String email) throws Exception {
        return body(send(post("/api/auth/login"), null, Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isOk()))
                .get("accessToken").asText();
    }

    private String addCityAddress(String token) throws Exception {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("label", "Home");
        address.put("receiverName", "Keo Bopha");
        address.put("phone", "012345678");
        address.put("deliveryType", "CITY");
        address.put("province", "Phnom Penh");
        address.put("latitude", OrderFixtures.PP_LAT.toPlainString());
        address.put("longitude", OrderFixtures.PP_LNG.toPlainString());
        address.put("street", "St. 13");
        return body(send(post("/api/addresses"), token, address).andExpect(status().isCreated()))
                .get("publicId").asText();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String accessToken, Object body) throws Exception {
        if (accessToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        return mockMvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
