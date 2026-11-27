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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 end to end, with real JWTs (no TestAuth): supplier -> category -> product (stock 0, out of stock)
 * -> image -> purchase order -> receive -> storefront shows it in stock with its main image
 * -> damage adjustment -> movement history has both rows -> product is in the low-stock list.
 */
class CatalogInventoryEndToEndTest extends IntegrationTest {

    private static final String PASSWORD = "Password123!";
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void catalogInventoryFlow_receiveThenDamage_storefrontStockHistoryAndLowStockAgree() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String manager = loginAsNew(Role.MANAGER);

        // 1. supplier and category
        String supplierId = body(send(post("/api/admin/suppliers"), manager,
                Map.of("name", "Angkor Dairy " + id, "phone", "012345678"))
                .andExpect(status().isCreated()))
                .get("publicId").asText();
        String categorySlug = body(send(post("/api/admin/categories"), manager, Map.of("name", "Dairy " + id))
                .andExpect(status().isCreated()))
                .get("slug").asText();

        // 2. product: starts at 0 even if the request sends a quantity
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("sku", "MILK-" + id);
        product.put("name", "Fresh Milk " + id);
        product.put("categorySlug", categorySlug);
        product.put("supplierPublicId", supplierId);
        product.put("costPrice", "1.20");
        product.put("sellPrice", "1.75");
        product.put("minStock", 10);
        product.put("quantity", 99);
        JsonNode created = body(send(post("/api/admin/products"), manager, product)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(0))
                .andExpect(jsonPath("$.costPrice").value(1.2)));
        String productId = created.get("publicId").asText();
        String slug = created.get("slug").asText();

        // the storefront shows it, out of stock, without cost price, supplier or exact stock
        send(get("/api/public/products/" + slug), null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(false))
                .andExpect(jsonPath("$.costPrice").doesNotExist())
                .andExpect(jsonPath("$.supplier").doesNotExist())
                .andExpect(jsonPath("$.quantity").doesNotExist());

        // 3. image upload: the first one becomes main
        String imageUrl = body(mockMvc.perform(multipart("/api/admin/products/" + productId + "/images")
                        .file(png())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + manager))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.main").value(true)))
                .get("imageUrl").asText();

        // 4. purchase order: the server calculates the total (12 × 1.80), the client sends no total
        String poNo = body(send(post("/api/admin/purchase-orders"), manager, Map.of(
                "supplierPublicId", supplierId,
                "items", List.of(Map.of("productPublicId", productId, "quantity", 12, "unitCost", "1.80"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(21.6)))
                .get("poNo").asText();
        send(get("/api/admin/products/" + productId), manager, null)
                .andExpect(jsonPath("$.quantity").value(0)); // nothing until it is received

        // 5. receive: stock goes up once; a second receive is refused
        send(post("/api/admin/purchase-orders/" + poNo + "/receive"), manager, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        send(post("/api/admin/purchase-orders/" + poNo + "/receive"), manager, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        send(get("/api/admin/products/" + productId), manager, null)
                .andExpect(jsonPath("$.quantity").value(12));

        // 6. the storefront now shows it in stock, with its main image, in search and on its page
        send(get("/api/public/products").param("category", categorySlug).param("inStock", "true"), null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value(slug))
                .andExpect(jsonPath("$.content[0].inStock").value(true))
                .andExpect(jsonPath("$.content[0].mainImageUrl").value(imageUrl));
        send(get("/api/public/products/" + slug), null, null)
                .andExpect(jsonPath("$.inStock").value(true))
                .andExpect(jsonPath("$.images[0].url").value(imageUrl))
                .andExpect(jsonPath("$.images[0].main").value(true));

        // 7. damage adjustment: 5 broken bottles
        send(post("/api/admin/inventory/adjustments"), manager, Map.of(
                "productPublicId", productId, "change", -5, "reason", "DAMAGE", "note", "Broken in delivery"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value(7));

        // 8. movement history (newest first): exactly the DAMAGE and the PURCHASE rows
        send(get("/api/admin/inventory/movements").param("product", productId), manager, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].type").value("ADJUST"))
                .andExpect(jsonPath("$.content[0].reason").value("DAMAGE"))
                .andExpect(jsonPath("$.content[0].quantity").value(-5))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(7))
                .andExpect(jsonPath("$.content[1].type").value("IN"))
                .andExpect(jsonPath("$.content[1].reason").value("PURCHASE"))
                .andExpect(jsonPath("$.content[1].quantity").value(12))
                .andExpect(jsonPath("$.content[1].balanceAfter").value(12))
                .andExpect(jsonPath("$.content[1].referenceNo").value(poNo));

        // 9. 7 left with a minimum of 10: it is on the low-stock list
        send(get("/api/admin/inventory/low-stock").param("size", "100"), manager, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.publicId == '" + productId + "')].quantity").value(hasItem(7)))
                .andExpect(jsonPath("$.content[?(@.publicId == '" + productId + "')].minStock").value(hasItem(10)));

        // a customer can browse the storefront but not the admin side
        String customer = loginAsNew(Role.CUSTOMER);
        send(get("/api/public/products/" + slug), customer, null).andExpect(status().isOk());
        send(get("/api/admin/inventory/low-stock"), customer, null).andExpect(status().isForbidden());
    }

    /** Staff accounts can't self-register, so create one directly and log in through the API. */
    private String loginAsNew(Role role) throws Exception {
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

        return body(send(post("/api/auth/login"), null, Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isOk()))
                .get("accessToken").asText();
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

    private static MockMultipartFile png() {
        byte[] data = new byte[128];
        System.arraycopy(PNG_MAGIC, 0, data, 0, PNG_MAGIC.length);
        return new MockMultipartFile("file", "milk.png", "image/png", data);
    }
}
