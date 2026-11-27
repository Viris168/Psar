package com.psar;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.order.job.OrderExpiryJob;
import com.psar.order.repository.OrderRepository;
import com.psar.payment.gateway.KhqrWebhookParser;
import com.psar.payment.job.PaymentCheckJob;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.support.PaymentFixtures;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import com.psar.user.entity.UserProfile;
import com.psar.user.repository.UserProfileRepository;
import com.psar.user.repository.UserRepository;
import kh.gov.nbc.bakong_khqr.BakongKHQR;
import kh.gov.nbc.bakong_khqr.model.KHQRDecodeData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * Phase 5 end to end (the MVP), with real JWTs, the real KHQR SDK, signed webhooks and the real jobs:
 * <ol>
 *   <li>KHQR paid → PAID, staff + customer notified; duplicates by simulation and webhook change nothing;
 *       admin refund → CANCELLED, stock back, payment REFUNDED;</li>
 *   <li>KHQR not paid in time → expired; paid later → LATE, admins notified, marked refunded by hand;</li>
 *   <li>COD delivered → payment SUCCESS (cash collected);</li>
 *   <li>KHQR delivered, then refunded with restock;</li>
 *   <li>hostile / wrong input: bad signature, wrong amount, unknown QR, another customer's payment.</li>
 * </ol>
 * Same properties as {@code PaymentWebTest}, so the cached test context is shared.
 */
@TestPropertySource(properties = {
        "app.payment.webhook.secret=" + PaymentEndToEndTest.WEBHOOK_SECRET,
        "app.payment.simulate.enabled=true"
})
class PaymentEndToEndTest extends IntegrationTest {

    static final String WEBHOOK_SECRET = "test-webhook-secret";
    private static final String PASSWORD = "Password123!";
    private static final String WEBHOOK = "/api/payments/webhook/bakong-khqr";

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private PaymentFixtures payments;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderExpiryJob orderExpiryJob;

    @Autowired
    private PaymentCheckJob paymentCheckJob;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Product rice;
    private Product oil;
    private String admin;
    private String staff;

    @BeforeEach
    void setUp() throws Exception {
        Category category = fixtures.category("E2E payments " + UUID.randomUUID().toString().substring(0, 6));
        rice = fixtures.product(category, "Jasmine Rice 5kg", "6.25", 20, 0);
        oil = fixtures.product(category, "Cooking Oil 1L", "1.50", 20, 0);
        admin = loginAs(Role.ADMIN);
        staff = loginAs(Role.STAFF);
    }

    // ---- 1. paid, duplicates ignored, refunded ---------------------------------------------------------

    @Test
    void khqr_paidThenDuplicatesThenRefund_stockAndMoneyAgree() throws Exception {
        String customer = registerCustomer();
        JsonNode checkout = checkout(customer, Map.of(rice, 2, oil, 1), "KHQR");
        String orderId = checkout.get("publicId").asText();
        String orderNo = checkout.get("orderNo").asText();
        BigDecimal total = checkout.get("totalAmount").decimalValue();
        JsonNode payment = checkout.get("payment");
        String paymentId = payment.get("publicId").asText();

        // the checkout returns a real, valid KHQR for exactly this order
        String qr = payment.get("qrString").asText();
        assertThat(BakongKHQR.verify(qr).getData().isValid()).isTrue();
        KHQRDecodeData decoded = BakongKHQR.decode(qr).getData();
        assertThat(new BigDecimal(decoded.getTransactionAmount())).isEqualByComparingTo(total);
        assertThat(decoded.getBillNumber()).isEqualTo(orderNo);
        assertThat(payment.has("providerRef")).isFalse();
        assertThat(payment.toString()).doesNotContain(adminPayment(paymentId).get("providerRef").asText());
        assertThat(fixtures.quantityOf(rice)).isEqualTo(18);

        // the customer pays (simulated) → APPLIED, order PAID
        send(post("/api/payments/" + paymentId + "/simulate"), customer, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        send(get("/api/orders/" + orderId), customer, null)
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"));
        send(get("/api/orders/" + orderId + "/payment"), customer, null)
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.qrString").doesNotExist()) // nothing left to scan
                .andExpect(jsonPath("$.paidAt").exists());
        assertThat(notifications(staff, "NEW_ORDER", orderNo)).hasSize(1);
        assertThat(notifications(customer, "ORDER_PAID", orderNo)).hasSize(1);

        // the same payment again, by simulation and by a signed webhook: DUPLICATE, nothing changes
        send(post("/api/payments/" + paymentId + "/simulate"), customer, null)
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        String md5 = adminPayment(paymentId).get("providerRef").asText();
        webhook(md5, "TX-" + UUID.randomUUID(), total, "USD", WEBHOOK_SECRET)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        assertThat(notifications(staff, "NEW_ORDER", orderNo)).hasSize(1);
        assertThat(eventOutcomes(paymentId)).containsExactly("APPLIED", "DUPLICATE", "DUPLICATE");

        // refund: staff may not, admin may; once
        Map<String, Object> refund = Map.of("reason", "Customer changed their mind", "restock", false);
        send(post("/api/admin/orders/" + orderId + "/refund"), staff, refund).andExpect(status().isForbidden());
        send(post("/api/admin/orders/" + orderId + "/refund"), admin, refund).andExpect(status().isOk());
        send(post("/api/admin/orders/" + orderId + "/refund"), admin, refund).andExpect(status().isConflict());

        send(get("/api/orders/" + orderId), customer, null)
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));
        JsonNode refunded = adminPayment(paymentId);
        assertThat(refunded.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(refunded.get("refundReason").asText()).isEqualTo("Customer changed their mind");
        assertThat(refunded.get("refundedAt").isNull()).isFalse();
        assertThat(fixtures.quantityOf(rice)).isEqualTo(20); // released exactly once
        assertThat(fixtures.quantityOf(oil)).isEqualTo(20);

        // a refunded payment can't be "paid" again
        send(post("/api/payments/" + paymentId + "/simulate"), customer, null)
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"))
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    // ---- 2. expired, then paid late, refunded by hand -----------------------------------------------------

    @Test
    void khqr_expiredThenPaidLate_refundRequiredAdminsNotifiedMarkedRefunded() throws Exception {
        String customer = registerCustomer();
        JsonNode checkout = checkout(customer, Map.of(oil, 3), "KHQR");
        String orderId = checkout.get("publicId").asText();
        String orderNo = checkout.get("orderNo").asText();
        String paymentId = checkout.get("payment").get("publicId").asText();
        assertThat(fixtures.quantityOf(oil)).isEqualTo(17);

        // not paid in 15 minutes: the expiry job cancels it, stock back, QR gone
        clock.advance(Duration.ofMinutes(16));
        orderExpiryJob.expireUnpaidOrders();
        send(get("/api/orders/" + orderId), customer, null)
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));
        send(get("/api/orders/" + orderId + "/payment"), customer, null)
                .andExpect(jsonPath("$.qrString").doesNotExist());
        assertThat(fixtures.quantityOf(oil)).isEqualTo(20);

        // the customer pays anyway; the check job finds it at Bakong → LATE
        payments.payAtBakong(orderRepository.findByPublicId(UUID.fromString(orderId)).orElseThrow());
        paymentCheckJob.checkPayments();

        JsonNode late = adminPayment(paymentId);
        assertThat(late.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(late.get("refundRequired").asBoolean()).isTrue();
        assertThat(late.get("orderStatus").asText()).isEqualTo("CANCELLED"); // the order is not revived
        assertThat(eventOutcomes(paymentId)).containsExactly("LATE");
        assertThat(fixtures.quantityOf(oil)).isEqualTo(20); // no stock taken for a cancelled order
        assertThat(notifications(admin, "PAYMENT_LATE", orderNo)).hasSize(1);
        assertThat(notifications(staff, "PAYMENT_LATE", orderNo)).isEmpty();
        assertThat(notifications(staff, "NEW_ORDER", orderNo)).isEmpty();

        // the admin finds it in the "refund required" list and records the manual refund
        assertThat(adminPaymentIds("refundRequired=true")).contains(paymentId);
        send(post("/api/admin/payments/" + paymentId + "/refunded"), admin,
                Map.of("reason", "Returned by ABA transfer"))
                .andExpect(status().isOk());
        JsonNode settled = adminPayment(paymentId);
        assertThat(settled.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(settled.get("refundRequired").asBoolean()).isFalse();
        assertThat(adminPaymentIds("refundRequired=true")).doesNotContain(paymentId);

        // the job checking again finds nothing new to do
        clock.advance(Duration.ofMinutes(2));
        paymentCheckJob.checkPayments();
        assertThat(eventOutcomes(paymentId)).containsExactly("LATE");
    }

    // ---- 3. cash on delivery ------------------------------------------------------------------------------

    @Test
    void cod_delivered_paymentCollected() throws Exception {
        String customer = registerCustomer();
        JsonNode checkout = checkout(customer, Map.of(rice, 1), "CASH_ON_DELIVERY");
        String orderId = checkout.get("publicId").asText();
        assertThat(checkout.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(checkout.get("payment").get("qrString").isNull()).isTrue();

        fulfil(orderId);

        send(get("/api/orders/" + orderId), customer, null)
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"));
        send(get("/api/orders/" + orderId + "/payment"), customer, null)
                .andExpect(jsonPath("$.method").value("CASH_ON_DELIVERY"))
                .andExpect(jsonPath("$.paidAt").exists());
    }

    // ---- 4. delivered, then refunded with restock -----------------------------------------------------------

    @Test
    void khqr_deliveredThenRefundedWithRestock_stockBack() throws Exception {
        String customer = registerCustomer();
        JsonNode checkout = checkout(customer, Map.of(rice, 4), "KHQR");
        String orderId = checkout.get("publicId").asText();
        String paymentId = checkout.get("payment").get("publicId").asText();
        send(post("/api/payments/" + paymentId + "/simulate"), customer, null)
                .andExpect(jsonPath("$.outcome").value("APPLIED"));
        fulfil(orderId);
        assertThat(fixtures.quantityOf(rice)).isEqualTo(16);

        send(post("/api/admin/orders/" + orderId + "/refund"), admin,
                Map.of("reason", "Damaged bag, returned", "restock", true))
                .andExpect(status().isOk());

        send(get("/api/orders/" + orderId), customer, null)
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));
        assertThat(fixtures.quantityOf(rice)).isEqualTo(20);
        JsonNode timeline = body(send(get("/api/orders/" + orderId), customer, null)).get("timeline");
        List<String> statuses = new ArrayList<>();
        timeline.forEach(entry -> statuses.add(entry.get("status").asText()));
        assertThat(statuses).containsExactly("PENDING_PAYMENT", "PAID", "PROCESSING", "OUT_FOR_DELIVERY",
                "DELIVERED", "REFUNDED");
    }

    // ---- 5. hostile or wrong input ----------------------------------------------------------------------

    @Test
    void webhookAndOwnership_badInput_neverChangesAPayment() throws Exception {
        String customer = registerCustomer();
        String stranger = registerCustomer();
        JsonNode checkout = checkout(customer, Map.of(oil, 2), "KHQR");
        String orderId = checkout.get("publicId").asText();
        String paymentId = checkout.get("payment").get("publicId").asText();
        BigDecimal total = checkout.get("totalAmount").decimalValue();
        String md5 = adminPayment(paymentId).get("providerRef").asText();

        // bad / missing signature → 401, recorded as REJECTED without touching the payment
        webhook(md5, "TX-forged", total, "USD", "not-the-secret").andExpect(status().isUnauthorized());
        mockMvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(webhookBody(md5, "TX-x", total, "USD")))
                .andExpect(status().isUnauthorized());
        // signed, but the wrong amount or currency → 200 AMOUNT_MISMATCH, payment untouched
        webhook(md5, "TX-short", total.subtract(new BigDecimal("0.01")), "USD", WEBHOOK_SECRET)
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("AMOUNT_MISMATCH"));
        webhook(md5, "TX-khr", total, "KHR", WEBHOOK_SECRET)
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("AMOUNT_MISMATCH"));
        // signed, for a QR we never issued → 200 UNKNOWN_PAYMENT
        webhook("0".repeat(32), "TX-unknown", total, "USD", WEBHOOK_SECRET)
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UNKNOWN_PAYMENT"));
        // unknown provider in the path → 404
        mockMvc.perform(post("/api/payments/webhook/paypal").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        JsonNode untouched = adminPayment(paymentId);
        assertThat(untouched.get("status").asText()).isEqualTo("PENDING");
        assertThat(untouched.get("transactionRef").isNull()).isTrue();
        assertThat(eventOutcomes(paymentId)).containsExactly("AMOUNT_MISMATCH", "AMOUNT_MISMATCH");

        // another customer can't see or pay someone else's order
        send(get("/api/orders/" + orderId + "/payment"), stranger, null).andExpect(status().isNotFound());
        send(post("/api/payments/" + paymentId + "/simulate"), stranger, null).andExpect(status().isNotFound());
        // staff can't see payments, customers can't use the admin side
        send(get("/api/admin/payments/" + paymentId), staff, null).andExpect(status().isForbidden());
        send(get("/api/admin/payments"), customer, null).andExpect(status().isForbidden());

        // the genuine webhook still works afterwards
        webhook(md5, "TX-" + UUID.randomUUID(), total, "USD", WEBHOOK_SECRET)
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        send(get("/api/orders/" + orderId), customer, null).andExpect(jsonPath("$.status").value("PAID"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /** Staff take an order from CONFIRMED / PAID to DELIVERED (CITY: driver first). */
    private void fulfil(String orderId) throws Exception {
        String url = "/api/admin/orders/" + orderId;
        send(patch(url + "/status"), staff, Map.of("status", "PROCESSING")).andExpect(status().isOk());
        send(put(url + "/driver"), staff, Map.of("driverPublicId", orders.driver().getPublicId()))
                .andExpect(status().isOk());
        send(patch(url + "/status"), staff, Map.of("status", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        send(patch(url + "/status"), staff, Map.of("status", "DELIVERED")).andExpect(status().isOk());
    }

    private JsonNode checkout(String token, Map<Product, Integer> lines, String method) throws Exception {
        String address = addCityAddress(token);
        for (Map.Entry<Product, Integer> line : lines.entrySet()) {
            send(post("/api/cart/items"), token,
                    Map.of("productPublicId", line.getKey().getPublicId(), "quantity", line.getValue()))
                    .andExpect(status().isOk());
        }
        return body(send(post("/api/checkout"), token, Map.of("addressPublicId", address, "paymentMethod", method))
                .andExpect(status().isCreated()));
    }

    private ResultActions webhook(String md5, String transactionRef, BigDecimal amount, String currency,
                                  String secret) throws Exception {
        byte[] body = webhookBody(md5, transactionRef, amount, currency);
        return mockMvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body)
                .header(KhqrWebhookParser.SIGNATURE_HEADER, KhqrWebhookParser.sign(body, secret)));
    }

    private static byte[] webhookBody(String md5, String transactionRef, BigDecimal amount, String currency) {
        return ("{\"md5\":\"" + md5 + "\",\"hash\":\"" + transactionRef + "\",\"amount\":" + amount.toPlainString()
                + ",\"currency\":\"" + currency + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode adminPayment(String paymentId) throws Exception {
        return body(send(get("/api/admin/payments/" + paymentId), admin, null).andExpect(status().isOk()));
    }

    private List<String> eventOutcomes(String paymentId) throws Exception {
        List<String> outcomes = new ArrayList<>();
        adminPayment(paymentId).get("events").forEach(e -> outcomes.add(e.get("outcome").asText()));
        return outcomes;
    }

    private List<String> adminPaymentIds(String query) throws Exception {
        JsonNode page = body(send(get("/api/admin/payments?" + query + "&size=100"), admin, null)
                .andExpect(status().isOk()));
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(p -> ids.add(p.get("publicId").asText()));
        return ids;
    }

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

    private String registerCustomer() throws Exception {
        String email = "e2e-payer-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        send(post("/api/auth/register"), null, Map.of("email", email, "password", PASSWORD, "fullName", "Keo Bopha"))
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
}
