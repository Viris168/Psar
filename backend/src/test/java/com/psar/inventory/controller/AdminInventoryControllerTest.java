package com.psar.inventory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.repository.ProductRepository;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.repository.StockMovementRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.TestAuth;
import com.psar.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminInventoryControllerTest extends IntegrationTest {

    private static final String URL = "/api/admin/inventory";

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ProductRepository productRepository;

    private User manager;
    private Category category;

    @BeforeEach
    void setUp() {
        manager = fixtures.manager();
        category = fixtures.category("Inventory api tests");
    }

    @Test
    void adjust_damage_reducesStockAndReturns201Movement() throws Exception {
        Product product = fixtures.product(category, "Eggs 30", "4.00", 10, 0);

        mockMvc.perform(post(URL + "/adjustments").with(TestAuth.as(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(adjustment(product, -3, "DAMAGE", "Dropped a tray"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("ADJUST"))
                .andExpect(jsonPath("$.quantity").value(-3))
                .andExpect(jsonPath("$.balanceAfter").value(7))
                .andExpect(jsonPath("$.reason").value("DAMAGE"))
                .andExpect(jsonPath("$.note").value("Dropped a tray"))
                .andExpect(jsonPath("$.product.publicId").value(product.getPublicId().toString()));

        assertThat(fixtures.quantityOf(product)).isEqualTo(7);
    }

    @Test
    void adjust_damageMoreThanInStock_returns409AndNoMovement() throws Exception {
        Product product = fixtures.product(category, "Milk", "1.50", 2, 0);

        mockMvc.perform(post(URL + "/adjustments").with(TestAuth.as(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(adjustment(product, -5, "DAMAGE", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(fixtures.quantityOf(product)).isEqualTo(2);
        assertThat(stockMovementRepository.count((root, query, cb) ->
                cb.equal(root.get("productId"), product.getId()))).isZero();
    }

    @Test
    void adjust_nonManualReasonOrZero_returns400() throws Exception {
        Product product = fixtures.product(category, "Sugar", "1.00", 5, 0);

        mockMvc.perform(post(URL + "/adjustments").with(TestAuth.as(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(adjustment(product, 5, "PURCHASE", null))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL + "/adjustments").with(TestAuth.as(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(adjustment(product, 0, "CORRECTION", null))))
                .andExpect(status().isBadRequest());
        assertThat(fixtures.quantityOf(product)).isEqualTo(5);
    }

    @Test
    void movements_filteredByProduct_newestFirstWithBalanceAfter() throws Exception {
        Product product = fixtures.product(category, "Coffee", "3.00", 0, 0);
        Product other = fixtures.product(category, "Tea", "2.00", 0, 0);
        String poNo = createAndReceive(product, 10);
        adjust(other, 4, "CORRECTION");
        adjust(product, -3, "DAMAGE");
        adjust(product, 1, "RETURN");

        mockMvc.perform(get(URL + "/movements").with(TestAuth.as(manager))
                        .param("product", product.getPublicId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].reason").value("RETURN"))
                .andExpect(jsonPath("$.content[0].quantity").value(1))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(8))
                .andExpect(jsonPath("$.content[1].reason").value("DAMAGE"))
                .andExpect(jsonPath("$.content[1].quantity").value(-3))
                .andExpect(jsonPath("$.content[1].balanceAfter").value(7))
                .andExpect(jsonPath("$.content[2].type").value("IN"))
                .andExpect(jsonPath("$.content[2].reason").value("PURCHASE"))
                .andExpect(jsonPath("$.content[2].quantity").value(10))
                .andExpect(jsonPath("$.content[2].balanceAfter").value(10))
                .andExpect(jsonPath("$.content[2].referenceNo").value(poNo))
                .andExpect(jsonPath("$.content[2].product.name").value("Coffee"));

        mockMvc.perform(get(URL + "/movements").with(TestAuth.as(manager))
                        .param("product", product.getPublicId().toString())
                        .param("type", "ADJUST")
                        .param("reason", "DAMAGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get(URL + "/movements").with(TestAuth.as(manager))
                        .param("product", product.getPublicId().toString())
                        .param("from", "2000-01-01T00:00:00Z")
                        .param("to", "2000-01-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void lowStock_returnsOnlyActiveAtOrBelowMin() throws Exception {
        Product low = fixtures.product(category, "Low eggs", "3.00", 2, 5);
        Product exactlyMin = fixtures.product(category, "Min bread", "1.00", 5, 5);
        Product enough = fixtures.product(category, "Plenty water", "0.50", 50, 5);
        Product inactive = fixtures.product(category, "Inactive old", "1.00", 0, 5);
        inactive.setActive(false);
        productRepository.save(inactive);

        List<String> ids = new ArrayList<>();
        int page = 0;
        JsonNode body;
        do {
            String response = mockMvc.perform(get(URL + "/low-stock").with(TestAuth.as(manager))
                            .param("page", String.valueOf(page++)).param("size", "100"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            body = objectMapper.readTree(response);
            body.get("content").forEach(p -> {
                assertThat(p.has("id")).isFalse();
                assertThat(p.get("quantity").asInt()).isLessThanOrEqualTo(p.get("minStock").asInt());
                ids.add(p.get("publicId").asText());
            });
        } while (page < body.get("totalPages").asInt());

        assertThat(ids).contains(low.getPublicId().toString(), exactlyMin.getPublicId().toString())
                .doesNotContain(enough.getPublicId().toString(), inactive.getPublicId().toString());
    }

    @Test
    void inventory_staff_returns403() throws Exception {
        Product product = fixtures.product(category, "Staff test", "1.00", 5, 0);

        mockMvc.perform(get(URL + "/movements").with(TestAuth.asRole(1L, "STAFF")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/low-stock").with(TestAuth.asRole(1L, "STAFF")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/adjustments").with(TestAuth.asRole(1L, "STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(adjustment(product, -1, "DAMAGE", null))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/low-stock").with(TestAuth.asRole(1L, "CUSTOMER")))
                .andExpect(status().isForbidden());
        assertThat(fixtures.quantityOf(product)).isEqualTo(5);
    }

    private Map<String, Object> adjustment(Product product, int change, String reason, String note) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("productPublicId", product.getPublicId());
        body.put("change", change);
        body.put("reason", reason);
        body.put("note", note);
        return body;
    }

    private void adjust(Product product, int change, String reason) throws Exception {
        mockMvc.perform(post(URL + "/adjustments").with(TestAuth.as(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(adjustment(product, change, reason, null))))
                .andExpect(status().isCreated());
    }

    private String createAndReceive(Product product, int quantity) throws Exception {
        Map<String, Object> body = Map.of(
                "supplierPublicId", fixtures.supplier("Movement supplier").getPublicId(),
                "items", List.of(Map.of("productPublicId", product.getPublicId(), "quantity", quantity,
                        "unitCost", "1.00")));
        String response = mockMvc.perform(post("/api/admin/purchase-orders").with(TestAuth.as(manager))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String poNo = objectMapper.readTree(response).get("poNo").asText();
        mockMvc.perform(post("/api/admin/purchase-orders/" + poNo + "/receive").with(TestAuth.as(manager)))
                .andExpect(status().isOk());
        return poNo;
    }
}
