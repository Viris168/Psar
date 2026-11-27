package com.psar;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.order.job.OrderExpiryJob;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 end to end, with real JWTs (no TestAuth):
 * <ol>
 *   <li>a customer fills a cart, gets a quote and checks out with COD: stock goes down, the cart empties,
 *       staff get a NEW_ORDER notification;</li>
 *   <li>staff take it through PROCESSING → driver → OUT_FOR_DELIVERY → DELIVERED; the customer sees the
 *       timeline and gets a notification for each step;</li>
 *   <li>a second customer checks out with KHQR and doesn't pay: 16 minutes later the expiry job cancels it,
 *       the stock comes back and the payment is CANCELLED.</li>
 * </ol>
 */
class CheckoutOrderEndToEndTest extends IntegrationTest {

    private static final String PASSWORD = "Password123!";

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private OrderExpiryJob orderExpiryJob;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void checkoutAndFulfilment_codDeliveredAndUnpaidKhqrExpired_stockAndNotificationsAgree() throws Exception {
        Category category = fixtures.category("E2E groceries");
        Product rice = fixtures.product(category, "Jasmine Rice 5kg", "2.50", 10, 0);
        Product oil = fixtures.product(category, "Cooking Oil 1L", "1.25", 5, 0);
        String staff = loginAs(Role.STAFF);
        String driverId = orders.driver().getPublicId().toString();

        // ---- 1. customer A: cart → quote → COD checkout --------------------------------------------
        String customerA = registerCustomer();
        String addressA = addCityAddress(customerA);
        send(post("/api/cart/items"), customerA, Map.of("productPublicId", rice.getPublicId(), "quantity", 2))
                .andExpect(status().isOk());
        send(post("/api/cart/items"), customerA, Map.of("productPublicId", oil.getPublicId(), "quantity", 3))
                .andExpect(status().isOk());
        send(get("/api/cart"), customerA, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(8.75));

        JsonNode quote = body(send(get("/api/checkout/quote").param("addressPublicId", addressA)
                .param("paymentMethod", "CASH_ON_DELIVERY"), customerA, null).andExpect(status().isOk()));
        BigDecimal quotedTotal = quote.get("total").decimalValue();
        assertThat(quote.get("subtotal").decimalValue()).isEqualByComparingTo("8.75");
        assertThat(quotedTotal).isEqualByComparingTo(new BigDecimal("8.75").add(quote.get("deliveryFee").decimalValue()));

        JsonNode orderA = body(send(post("/api/checkout"), customerA,
                Map.of("addressPublicId", addressA, "paymentMethod", "CASH_ON_DELIVERY"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED")));
        String orderAId = orderA.get("publicId").asText();
        String orderANo = orderA.get("orderNo").asText();
        assertThat(orderA.get("totalAmount").decimalValue()).isEqualByComparingTo(quotedTotal);
        assertThat(fixtures.quantityOf(rice)).isEqualTo(8);
        assertThat(fixtures.quantityOf(oil)).isEqualTo(2);
        send(get("/api/cart"), customerA, null).andExpect(jsonPath("$.items").isEmpty());
        assertThat(notifications(staff, "NEW_ORDER", orderANo)).hasSize(1);

        // ---- 2. staff fulfil it; the customer follows along ------------------------------------------
        String admin = "/api/admin/orders/" + orderAId;
        changeStatus(staff, admin, "PROCESSING");
        send(patch(admin + "/status"), staff, Map.of("status", "OUT_FOR_DELIVERY"))
                .andExpect(status().isUnprocessableEntity()); // no driver yet
        send(put(admin + "/driver"), staff, Map.of("driverPublicId", driverId)).andExpect(status().isOk());
        changeStatus(staff, admin, "OUT_FOR_DELIVERY");
        changeStatus(staff, admin, "DELIVERED");

        JsonNode detail = body(send(get("/api/orders/" + orderAId), customerA, null).andExpect(status().isOk()));
        assertThat(detail.get("status").asText()).isEqualTo("DELIVERED");
        List<String> timeline = new ArrayList<>();
        detail.get("timeline").forEach(entry -> timeline.add(entry.get("status").asText()));
        assertThat(timeline).containsExactly("CONFIRMED", "PROCESSING", "OUT_FOR_DELIVERY", "DELIVERED");
        assertThat(notifications(customerA, "ORDER_STATUS_CHANGED", orderANo)).hasSize(3);

        // ---- 3. customer B: KHQR checkout, never paid ----------------------------------------------
        String customerB = registerCustomer();
        String addressB = addCityAddress(customerB);
        send(post("/api/cart/items"), customerB, Map.of("productPublicId", oil.getPublicId(), "quantity", 2))
                .andExpect(status().isOk());
        JsonNode orderB = body(send(post("/api/checkout"), customerB,
                Map.of("addressPublicId", addressB, "paymentMethod", "KHQR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.expiresAt").exists()));
        String orderBId = orderB.get("publicId").asText();
        String orderBNo = orderB.get("orderNo").asText();
        assertThat(fixtures.quantityOf(oil)).isZero();
        assertThat(notifications(staff, "NEW_ORDER", orderBNo)).isEmpty(); // staff hear about KHQR once paid
        send(get("/api/orders/" + orderBId), customerA, null).andExpect(status().isNotFound());

        // 16 minutes later the job runs
        clock.advance(Duration.ofMinutes(16));
        assertThat(orderExpiryJob.expireUnpaidOrders()).isPositive();

        send(get("/api/orders/" + orderBId), customerB, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));
        assertThat(fixtures.quantityOf(oil)).isEqualTo(2);
        assertThat(notifications(customerB, "ORDER_CANCELLED", orderBNo)).singleElement()
                .satisfies(n -> assertThat(n.get("message").asText()).contains("Not paid in time"));
        // running it again changes nothing
        orderExpiryJob.expireUnpaidOrders();
        assertThat(fixtures.quantityOf(oil)).isEqualTo(2);
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private void changeStatus(String token, String orderUrl, String status) throws Exception {
        send(patch(orderUrl + "/status"), token, Map.of("status", status))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(status));
    }

    private String registerCustomer() throws Exception {
        String email = "e2e-buyer-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        send(post("/api/auth/register"), null, Map.of("email", email, "password", PASSWORD, "fullName", "Chan Sophea"))
                .andExpect(status().isCreated());
        return login(email);
    }

    /** Staff accounts can't self-register, so create one directly and log in through the API. */
    private String loginAs(Role role) throws Exception {
        String email = "e2e-" + role.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@test.local";
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

    /** A CITY address at the shop's default location (first delivery fee band). */
    private String addCityAddress(String token) throws Exception {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("label", "Home");
        address.put("receiverName", "Chan Sophea");
        address.put("phone", "012345678");
        address.put("deliveryType", "CITY");
        address.put("province", "Phnom Penh");
        address.put("latitude", OrderFixtures.PP_LAT.toPlainString());
        address.put("longitude", OrderFixtures.PP_LNG.toPlainString());
        address.put("street", "St. 13");
        return body(send(post("/api/addresses"), token, address).andExpect(status().isCreated()))
                .get("publicId").asText();
    }

    /** This user's notifications of one type that mention the order number. */
    private List<JsonNode> notifications(String token, String type, String orderNo) throws Exception {
        JsonNode page = body(send(get("/api/notifications").param("size", "100"), token, null)
                .andExpect(status().isOk()));
        List<JsonNode> matches = new ArrayList<>();
        page.get("content").forEach(n -> {
            if (n.get("type").asText().equals(type)
                    && (n.get("title").asText() + n.get("message").asText()).contains(orderNo)) {
                matches.add(n);
            }
        });
        return matches;
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
}
