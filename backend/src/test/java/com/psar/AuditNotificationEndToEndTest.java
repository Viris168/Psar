package com.psar;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.support.IntegrationTest;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 end to end, with real JWTs (no TestAuth): manager logs in (LOGIN row) -> creates a product with
 * minStock 10 -> receives a PO of 12 -> damage -5 crosses the minimum -> the manager and an admin each get one
 * LOW_STOCK notification, staff none -> manager marks it read -> the admin's audit log shows the trail, and a
 * manager gets 403 on it.
 */
class AuditNotificationEndToEndTest extends IntegrationTest {

    private static final String PASSWORD = "Password123!";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void auditNotificationFlow_damageCrossesMinimum_managersNotifiedAndTrailAudited() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Login manager = login(Role.MANAGER);
        Login admin = login(Role.ADMIN);
        Login staff = login(Role.STAFF);

        // 1. supplier, category, product with a minimum of 10
        String supplierId = body(send(post("/api/admin/suppliers"), manager.token(),
                Map.of("name", "Mekong Foods " + id)).andExpect(status().isCreated())).get("publicId").asText();
        String categorySlug = body(send(post("/api/admin/categories"), manager.token(),
                Map.of("name", "Sauces " + id)).andExpect(status().isCreated())).get("slug").asText();
        String sku = "SAUCE-" + id.toUpperCase();
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("sku", sku);
        product.put("name", "Fish Sauce " + id);
        product.put("categorySlug", categorySlug);
        product.put("supplierPublicId", supplierId);
        product.put("costPrice", "0.90");
        product.put("sellPrice", "1.50");
        product.put("minStock", 10);
        String productId = body(send(post("/api/admin/products"), manager.token(), product)
                .andExpect(status().isCreated())).get("publicId").asText();

        // 2. receive 12: stock goes up, no low-stock notification yet
        String poNo = body(send(post("/api/admin/purchase-orders"), manager.token(), Map.of(
                "supplierPublicId", supplierId,
                "items", List.of(Map.of("productPublicId", productId, "quantity", 12, "unitCost", "0.90"))))
                .andExpect(status().isCreated())).get("poNo").asText();
        send(post("/api/admin/purchase-orders/" + poNo + "/receive"), manager.token(), null)
                .andExpect(status().isOk());
        assertThat(lowStockFor(manager, sku)).isEmpty();

        // 3. damage -5: 12 -> 7 crosses the minimum of 10
        send(post("/api/admin/inventory/adjustments"), manager.token(), Map.of(
                "productPublicId", productId, "change", -5, "reason", "DAMAGE", "note", "Leaking bottles"))
                .andExpect(status().isCreated());

        // 4. one notification each for the manager and the admin, none for staff
        List<JsonNode> managerInbox = lowStockFor(manager, sku);
        assertThat(managerInbox).singleElement().satisfies(n -> {
            assertThat(n.get("message").asText()).contains(sku).contains("7 left").contains("minimum 10");
            assertThat(n.get("link").asText()).isEqualTo("/admin/inventory/low-stock");
            assertThat(n.get("read").asBoolean()).isFalse();
        });
        assertThat(lowStockFor(admin, sku)).hasSize(1);
        assertThat(lowStockFor(staff, sku)).isEmpty();

        // another sale below the minimum doesn't notify again
        send(post("/api/admin/inventory/adjustments"), manager.token(), Map.of(
                "productPublicId", productId, "change", -1, "reason", "DAMAGE"))
                .andExpect(status().isCreated());
        assertThat(lowStockFor(manager, sku)).hasSize(1);

        // 5. the manager reads it; another user can't
        String notificationId = managerInbox.getFirst().get("publicId").asText();
        send(patch("/api/notifications/" + notificationId + "/read"), staff.token(), null)
                .andExpect(status().isNotFound());
        send(patch("/api/notifications/" + notificationId + "/read"), manager.token(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        send(get("/api/notifications/unread-count"), manager.token(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        // 6. the admin's audit log shows the trail, by the manager
        assertThat(audit(admin, "action=LOGIN&entity=AUTH&entityId=" + manager.publicId())).singleElement()
                .satisfies(row -> assertThat(row.get("actor").get("publicId").asText()).isEqualTo(manager.publicId()));
        assertThat(audit(admin, "entity=PRODUCT&entityId=" + productId)).singleElement().satisfies(row -> {
            assertThat(row.get("action").asText()).isEqualTo("CREATE");
            assertThat(row.get("actor").get("email").asText()).isEqualTo(manager.email());
        });
        List<JsonNode> poRows = audit(admin, "entity=PURCHASE_ORDER&entityId=" + poNo);
        assertThat(poRows).extracting(row -> row.get("action").asText())
                .containsExactlyInAnyOrder("CREATE", "UPDATE");
        assertThat(poRows).filteredOn(row -> row.get("action").asText().equals("UPDATE")).singleElement()
                .satisfies(row -> assertThat(row.get("details").get("status").get("to").asText()).isEqualTo("RECEIVED"));
        assertThat(audit(admin, "entity=STOCK_ADJUSTMENT&entityId=" + productId))
                .extracting(row -> row.get("details").get("change").asInt())
                .containsExactlyInAnyOrder(-5, -1);

        // 7. only admins read the audit log
        send(get("/api/admin/audit-logs"), manager.token(), null).andExpect(status().isForbidden());
        send(get("/api/admin/audit-logs"), staff.token(), null).andExpect(status().isForbidden());
    }

    private record Login(String token, String publicId, String email) {
    }

    /** Staff accounts can't self-register, so create one directly and log in through the API. */
    private Login login(Role role) throws Exception {
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

        String token = body(send(post("/api/auth/login"), null, Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isOk()))
                .get("accessToken").asText();
        return new Login(token, user.getPublicId().toString(), email);
    }

    /** This user's LOW_STOCK notifications about one product (other tests may notify the same roles). */
    private List<JsonNode> lowStockFor(Login user, String sku) throws Exception {
        JsonNode page = body(send(get("/api/notifications").param("size", "100"), user.token(), null)
                .andExpect(status().isOk()));
        List<JsonNode> matches = new ArrayList<>();
        page.get("content").forEach(n -> {
            if (n.get("type").asText().equals("LOW_STOCK") && n.get("message").asText().contains(sku)) {
                matches.add(n);
            }
        });
        return matches;
    }

    private List<JsonNode> audit(Login admin, String query) throws Exception {
        JsonNode page = body(send(get("/api/admin/audit-logs?" + query + "&size=100"), admin.token(), null)
                .andExpect(status().isOk()));
        List<JsonNode> rows = new ArrayList<>();
        page.get("content").forEach(rows::add);
        return rows;
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
