package com.psar.order.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.repository.ProductRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CartControllerTest extends IntegrationTest {

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void addToCart_sameProductTwice_addsUp() throws Exception {
        Product product = fixtures.product(fixtures.category("Cart adds up"), "Fish sauce", "1.25", 20, 0);
        User customer = orders.customer();

        add(customer, product, 2).andExpect(status().isOk());
        JsonNode cart = body(add(customer, product, 3).andExpect(status().isOk()));

        assertThat(cart.get("items")).hasSize(1);
        JsonNode line = cart.get("items").get(0);
        assertThat(line.get("productPublicId").asText()).isEqualTo(product.getPublicId().toString());
        assertThat(line.get("quantity").asInt()).isEqualTo(5);
        assertThat(line.get("lineTotal").decimalValue()).isEqualByComparingTo("6.25");
        assertThat(line.get("available").asBoolean()).isTrue();
        assertThat(line.get("inStock").asBoolean()).isTrue();
        assertThat(line.has("quantityInStock") || line.has("stock")).isFalse();
        assertThat(cart.get("subtotal").decimalValue()).isEqualByComparingTo("6.25");
        assertThat(cart.get("totalQuantity").asInt()).isEqualTo(5);
    }

    @Test
    void addToCart_moreThan99InOneLine_returns422() throws Exception {
        Product product = fixtures.product(fixtures.category("Cart max"), "Water", "0.50", 200, 0);
        User customer = orders.customer();

        add(customer, product, 60).andExpect(status().isOk());
        add(customer, product, 40).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
        add(customer, product, 100).andExpect(status().isBadRequest());
    }

    @Test
    void addToCart_inactiveProduct_returns422() throws Exception {
        Product product = fixtures.product(fixtures.category("Cart inactive"), "Old soap", "0.80", 5, 0);
        product.setActive(false);
        productRepository.save(product);
        User customer = orders.customer();

        add(customer, product, 1).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        mockMvc.perform(get("/api/cart").with(TestAuth.as(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void cart_priceChanged_showsNewPrice() throws Exception {
        Product product = fixtures.product(fixtures.category("Cart price"), "Coffee", "3.00", 10, 0);
        User customer = orders.customer();
        add(customer, product, 2).andExpect(status().isOk());

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        reloaded.setSellPrice(new BigDecimal("3.50"));
        productRepository.save(reloaded);

        JsonNode cart = body(mockMvc.perform(get("/api/cart").with(TestAuth.as(customer))).andExpect(status().isOk()));
        assertThat(cart.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("3.50");
        assertThat(cart.get("subtotal").decimalValue()).isEqualByComparingTo("7.00");
    }

    @Test
    void cart_productDeactivatedAfterAdding_lineUnavailableAndNotInSubtotal() throws Exception {
        Category category = fixtures.category("Cart unavailable");
        Product kept = fixtures.product(category, "Kept", "1.00", 10, 0);
        Product gone = fixtures.product(category, "Gone", "2.00", 0, 0);
        User customer = orders.customer();
        add(customer, kept, 1).andExpect(status().isOk());
        add(customer, gone, 1).andExpect(status().isOk());

        Product reloaded = productRepository.findById(gone.getId()).orElseThrow();
        reloaded.setActive(false);
        productRepository.save(reloaded);

        JsonNode cart = body(mockMvc.perform(get("/api/cart").with(TestAuth.as(customer))).andExpect(status().isOk()));
        JsonNode goneLine = cart.get("items").get(1);
        assertThat(goneLine.get("available").asBoolean()).isFalse();
        assertThat(goneLine.get("inStock").asBoolean()).isFalse();
        assertThat(cart.get("subtotal").decimalValue()).isEqualByComparingTo("1.00");
    }

    @Test
    void setQuantityRemoveClear_ownCart_updatesLines() throws Exception {
        Category category = fixtures.category("Cart edit");
        Product a = fixtures.product(category, "A", "1.00", 10, 0);
        Product b = fixtures.product(category, "B", "2.00", 10, 0);
        User customer = orders.customer();
        orders.cart(customer, Map.of(a, 1, b, 1));

        mockMvc.perform(put("/api/cart/items/{id}", a.getPublicId()).with(TestAuth.as(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("quantity", 4))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQuantity").value(5));
        mockMvc.perform(delete("/api/cart/items/{id}", b.getPublicId()).with(TestAuth.as(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(put("/api/cart/items/{id}", b.getPublicId()).with(TestAuth.as(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("quantity", 1))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/cart").with(TestAuth.as(customer)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/cart").with(TestAuth.as(customer)))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void addToCart_parallelAddsToNewCart_allSucceedAndAddUp() throws Exception {
        Product product = fixtures.product(fixtures.category("Cart race new"), "Rice", "1.00", 50, 0);
        User customer = orders.customer();

        assertThat(addInParallel(customer, product, 10)).containsOnly(200);

        mockMvc.perform(get("/api/cart").with(TestAuth.as(customer)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalQuantity").value(10));
    }

    @Test
    void addToCart_parallelAddsToExistingLine_noLostUpdates() throws Exception {
        Product product = fixtures.product(fixtures.category("Cart race line"), "Salt", "0.50", 50, 0);
        User customer = orders.customer();
        add(customer, product, 1).andExpect(status().isOk());

        assertThat(addInParallel(customer, product, 10)).containsOnly(200);

        mockMvc.perform(get("/api/cart").with(TestAuth.as(customer)))
                .andExpect(jsonPath("$.totalQuantity").value(11));
    }

    @Test
    void cart_staffUser_returns403() throws Exception {
        mockMvc.perform(get("/api/cart").with(TestAuth.asRole(1L, "STAFF")))
                .andExpect(status().isForbidden());
    }

    private ResultActions add(User customer, Product product, int quantity) throws Exception {
        return mockMvc.perform(post("/api/cart/items").with(TestAuth.as(customer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("productPublicId", product.getPublicId(), "quantity", quantity))));
    }

    /** Sends {@code times} "add 1" requests for the same customer at once; returns their HTTP statuses. */
    private List<Integer> addInParallel(User customer, Product product, int times) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(times);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < times; i++) {
                Callable<Integer> task = () -> {
                    start.await();
                    return add(customer, product, 1).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
