package com.psar.catalog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.repository.ProductRepository;
import com.psar.inventory.entity.Supplier;
import com.psar.inventory.repository.SupplierRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin products (/api/admin/products): read for staff, write for admin/manager. */
class AdminProductControllerTest extends IntegrationTest {

    private static final String ADMIN = "/api/admin/products";

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    private Category category;

    @BeforeEach
    void setUp() {
        category = fixtures.category("Admin products");
    }

    // ---- create ------------------------------------------------------------

    @Test
    void createProduct_startsWithZeroQuantity() throws Exception {
        Supplier supplier = fixtures.supplier("Supplier " + shortId());
        Map<String, Object> body = body("  sku-" + shortId() + " ", "Fresh Milk 1L");
        body.put("supplierPublicId", supplier.getPublicId());
        body.put("quantity", 50); // not part of the request: ignored

        JsonNode created = create(body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(0))
                .andExpect(jsonPath("$.minStock").value(10))
                .andExpect(jsonPath("$.unit").value("pcs"))
                .andExpect(jsonPath("$.costPrice").value(1.2))
                .andExpect(jsonPath("$.sellPrice").value(1.75))
                .andExpect(jsonPath("$.supplier.publicId").value(supplier.getPublicId().toString()))
                .andExpect(jsonPath("$.category.slug").value(category.getSlug()))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn().getResponse().getContentAsString().transform(this::read);

        assertThat(created.get("sku").asText()).isEqualTo(((String) body.get("sku")).trim().toUpperCase());
        assertThat(created.get("slug").asText()).startsWith("fresh-milk-1l");
        Product saved = productRepository.findByPublicId(UUID.fromString(created.get("publicId").asText())).orElseThrow();
        assertThat(fixtures.quantityOf(saved)).isZero();
    }

    @Test
    void createProduct_duplicateSkuDifferentCase_returns409() throws Exception {
        String sku = "DUP-" + shortId().toUpperCase();
        create(body(sku, "First")).andExpect(status().isCreated());

        create(body(sku.toLowerCase(), "Second"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"));
    }

    @Test
    void createProduct_sameSkuConcurrently_oneCreatedOtherSkuConflict() throws Exception {
        String sku = "RACE-" + shortId().toUpperCase();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> createOne = () -> {
                start.await();
                var response = create(body(sku, "Race " + UUID.randomUUID())).andReturn().getResponse();
                return response.getStatus() + (response.getStatus() == 409
                        ? " " + read(response.getContentAsString()).get("code").asText() : "");
            };
            Future<String> first = pool.submit(createOne);
            Future<String> second = pool.submit(createOne);
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("201", "409 SKU_ALREADY_EXISTS");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void createProduct_sameName_getsUniqueSlug() throws Exception {
        String name = "Same Name " + shortId();
        String base = name.toLowerCase().replace(' ', '-');

        create(body("A-" + shortId(), name)).andExpect(jsonPath("$.slug").value(base));
        create(body("B-" + shortId(), name)).andExpect(jsonPath("$.slug").value(base + "-2"));
        create(body("C-" + shortId(), name)).andExpect(jsonPath("$.slug").value(base + "-3"));
    }

    @Test
    void createProduct_khmerOnlyName_slugFromSku() throws Exception {
        String sku = "KH-" + shortId().toUpperCase();

        create(body(sku, "ទឹកដោះគោស្រស់"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(sku.toLowerCase()));
    }

    @Test
    void createProduct_inactiveSupplier_returns422() throws Exception {
        Supplier supplier = fixtures.supplier("Inactive " + shortId());
        supplier.setActive(false);
        supplierRepository.save(supplier);
        Map<String, Object> body = body("SUP-" + shortId(), "No supplier");
        body.put("supplierPublicId", supplier.getPublicId());

        create(body).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createProduct_inactiveCategory_returns422() throws Exception {
        Map<String, Object> body = body("CAT-" + shortId(), "No category");
        body.put("categorySlug", "does-not-exist-" + shortId());

        create(body).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createProduct_invalidPrices_returns400() throws Exception {
        Map<String, Object> body = body("BAD-" + shortId(), "Bad prices");
        body.put("sellPrice", 0);
        body.put("costPrice", -1);

        create(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.sellPrice").exists())
                .andExpect(jsonPath("$.details.costPrice").exists());
    }

    // ---- update / delete ---------------------------------------------------

    @Test
    void updateProduct_renameAndReprice_keepsSlugAndStock() throws Exception {
        Product product = fixtures.product(category, "Old name", "2.00", 7, 3);
        Map<String, Object> body = body(product.getSku(), "New name");
        body.put("sellPrice", "2.50");
        body.remove("active");

        mockMvc.perform(put(ADMIN + "/" + product.getPublicId()).with(TestAuth.asRole(1L, "MANAGER"))
                        .contentType(APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New name"))
                .andExpect(jsonPath("$.slug").value(product.getSlug()))
                .andExpect(jsonPath("$.sellPrice").value(2.5))
                .andExpect(jsonPath("$.quantity").value(7))
                .andExpect(jsonPath("$.minStock").value(3))
                .andExpect(jsonPath("$.active").value(true));
        assertThat(fixtures.quantityOf(product)).isEqualTo(7);
    }

    @Test
    void updateProduct_reactivateInInactiveCategory_returns422() throws Exception {
        Category closed = fixtures.category("Closed " + shortId());
        Product product = fixtures.product(closed, "Paused", "1.00", 0, 0);
        mockMvc.perform(delete(ADMIN + "/" + product.getPublicId()).with(TestAuth.asRole(1L, "ADMIN")))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/admin/categories/" + closed.getSlug()).with(TestAuth.asRole(1L, "ADMIN")))
                .andExpect(status().isNoContent());

        Map<String, Object> body = body(product.getSku(), "Paused");
        body.put("categorySlug", closed.getSlug());
        mockMvc.perform(put(ADMIN + "/" + product.getPublicId()).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(body)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(productRepository.findById(product.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void updateProduct_skuOfAnotherProduct_returns409() throws Exception {
        Product first = fixtures.product(category, "First", "1.00", 0, 0);
        Product second = fixtures.product(category, "Second", "1.00", 0, 0);

        mockMvc.perform(put(ADMIN + "/" + second.getPublicId()).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(body(first.getSku().toLowerCase(), "Second"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"));
    }

    @Test
    void deleteProduct_existing_returns204AndSoftDeletes() throws Exception {
        Product product = fixtures.product(category, "Doomed", "1.00", 0, 0);

        mockMvc.perform(delete(ADMIN + "/" + product.getPublicId()).with(TestAuth.asRole(1L, "ADMIN")))
                .andExpect(status().isNoContent());

        assertThat(productRepository.findById(product.getId()).orElseThrow().isActive()).isFalse();
        mockMvc.perform(get("/api/public/products/" + product.getSlug())).andExpect(status().isNotFound());
    }

    // ---- read / permissions ------------------------------------------------

    @Test
    void adminProducts_filters_includeInactiveAndCostPrice() throws Exception {
        Supplier supplier = fixtures.supplier("Filter supplier " + shortId());
        Product active = fixtures.product(category, "Active one", "4.00", 2, 0);
        Product inactive = fixtures.product(category, "Inactive one", "4.00", 0, 0);
        inactive.setActive(false);
        inactive.setSupplierId(supplier.getId());
        productRepository.save(inactive);

        mockMvc.perform(get(ADMIN).with(TestAuth.asRole(1L, "STAFF")).param("category", category.getSlug())
                        .param("active", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].publicId", contains(inactive.getPublicId().toString())))
                .andExpect(jsonPath("$.content[0].costPrice").value(2.0))
                .andExpect(jsonPath("$.content[0].supplier.name").value(supplier.getName()));
        mockMvc.perform(get(ADMIN).with(TestAuth.asRole(1L, "STAFF")).param("supplier", supplier.getPublicId().toString()))
                .andExpect(jsonPath("$.content[*].publicId", contains(inactive.getPublicId().toString())));
        mockMvc.perform(get(ADMIN + "/" + active.getPublicId()).with(TestAuth.asRole(1L, "STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(2));
    }

    @Test
    void adminProducts_staff_canReadButNotWrite() throws Exception {
        Product product = fixtures.product(category, "Staff view", "1.00", 0, 0);

        mockMvc.perform(get(ADMIN + "/" + product.getPublicId()).with(TestAuth.asRole(1L, "STAFF")))
                .andExpect(status().isOk());
        mockMvc.perform(post(ADMIN).with(TestAuth.asRole(1L, "STAFF"))
                        .contentType(APPLICATION_JSON).content(json(body("STAFF-" + shortId(), "Nope"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(ADMIN + "/" + product.getPublicId()).with(TestAuth.asRole(1L, "STAFF"))
                        .contentType(APPLICATION_JSON).content(json(body(product.getSku(), "Nope"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(ADMIN + "/" + product.getPublicId()).with(TestAuth.asRole(1L, "STAFF")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminProducts_customer_returns403() throws Exception {
        mockMvc.perform(get(ADMIN).with(TestAuth.asRole(1L, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminProducts_noLogin_returns401() throws Exception {
        mockMvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());
    }

    @Test
    void adminProduct_unknownPublicId_returns404() throws Exception {
        mockMvc.perform(get(ADMIN + "/" + UUID.randomUUID()).with(TestAuth.asRole(1L, "ADMIN")))
                .andExpect(status().isNotFound());
    }

    // ---- helpers -----------------------------------------------------------

    private ResultActions create(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(ADMIN).with(TestAuth.asRole(1L, "ADMIN"))
                .contentType(APPLICATION_JSON).content(json(body)));
    }

    private Map<String, Object> body(String sku, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", sku);
        body.put("name", name);
        body.put("categorySlug", category.getSlug());
        body.put("costPrice", "1.20");
        body.put("sellPrice", "1.75");
        body.put("active", true);
        return body;
    }

    private JsonNode read(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
