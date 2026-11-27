package com.psar.catalog.controller;

import com.psar.catalog.entity.Category;
import com.psar.catalog.repository.CategoryRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.TestAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Storefront tree (/api/public/categories) and admin CRUD (/api/admin/categories). */
class CategoryControllerTest extends IntegrationTest {

    private static final String PUBLIC = "/api/public/categories";
    private static final String ADMIN = "/api/admin/categories";

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private CategoryRepository categoryRepository;

    // ---- storefront --------------------------------------------------------

    @Test
    void publicTree_noLogin_activeParentsWithActiveChildren() throws Exception {
        Category parent = fixtures.category("Drinks");
        Category child = fixtures.category("Juice", parent);
        Category hiddenChild = fixtures.category("Old juice", parent);
        hiddenChild.setActive(false);
        categoryRepository.save(hiddenChild);
        Category hiddenParent = fixtures.category("Gone");
        hiddenParent.setActive(false);
        categoryRepository.save(hiddenParent);

        String node = "$[?(@.slug == '" + parent.getSlug() + "')]";
        mockMvc.perform(get(PUBLIC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug", hasItem(parent.getSlug())))
                .andExpect(jsonPath("$[*].slug", not(hasItem(child.getSlug()))))
                .andExpect(jsonPath("$[*].slug", not(hasItem(hiddenParent.getSlug()))))
                .andExpect(jsonPath(node + ".children[*].slug", hasItem(child.getSlug())))
                .andExpect(jsonPath(node + ".children[*].slug", not(hasItem(hiddenChild.getSlug()))));
    }

    // ---- admin -------------------------------------------------------------

    @Test
    void createCategory_validRequest_returns201WithGeneratedSlug() throws Exception {
        String token = shortId();
        mockMvc.perform(post(ADMIN).with(TestAuth.asRole(1L, "MANAGER"))
                        .contentType(APPLICATION_JSON).content(json(body("Fresh Fruit " + token, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("fresh-fruit-" + token))
                .andExpect(jsonPath("$.parentSlug").isEmpty())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void createCategory_subcategory_returnsParentSlug() throws Exception {
        Category parent = fixtures.category("Snacks");

        mockMvc.perform(post(ADMIN).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(body("Chips " + shortId(), parent.getSlug()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentSlug").value(parent.getSlug()));
    }

    @Test
    void createCategory_thirdLevel_returns422() throws Exception {
        Category top = fixtures.category("Level one");
        Category second = fixtures.category("Level two", top);

        mockMvc.perform(post(ADMIN).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(body("Level three", second.getSlug()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void updateCategory_parentWithChildrenMovedUnderAnother_returns422() throws Exception {
        Category top = fixtures.category("Has kids");
        fixtures.category("Kid", top);
        Category other = fixtures.category("Other top");

        mockMvc.perform(put(ADMIN + "/" + top.getSlug()).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(body("Has kids", other.getSlug()))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateCategory_rename_keepsSlug() throws Exception {
        Category category = fixtures.category("Before rename");

        mockMvc.perform(put(ADMIN + "/" + category.getSlug()).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(body("After rename", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("After rename"))
                .andExpect(jsonPath("$.slug").value(category.getSlug()));
    }

    @Test
    void deleteCategory_withActiveProducts_returns422() throws Exception {
        Category category = fixtures.category("Busy");
        fixtures.product(category, "Busy product", "1.00", 0, 0);

        mockMvc.perform(delete(ADMIN + "/" + category.getSlug()).with(TestAuth.asRole(1L, "ADMIN")))
                .andExpect(status().isUnprocessableEntity());

        assertThat(categoryRepository.findById(category.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void deleteCategory_withActiveSubcategory_returns422() throws Exception {
        Category top = fixtures.category("Parent busy");
        fixtures.category("Child busy", top);

        mockMvc.perform(delete(ADMIN + "/" + top.getSlug()).with(TestAuth.asRole(1L, "MANAGER")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deleteCategory_empty_returns204AndKeepsRowInactive() throws Exception {
        Category category = fixtures.category("Empty");

        mockMvc.perform(delete(ADMIN + "/" + category.getSlug()).with(TestAuth.asRole(1L, "MANAGER")))
                .andExpect(status().isNoContent());

        assertThat(categoryRepository.findById(category.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void adminCategories_staff_canReadButNotWrite() throws Exception {
        Category inactive = fixtures.category("Inactive listed");
        inactive.setActive(false);
        categoryRepository.save(inactive);

        mockMvc.perform(get(ADMIN).with(TestAuth.asRole(1L, "STAFF"))
                        .param("size", "100").param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].slug", hasItem(inactive.getSlug())));
        mockMvc.perform(post(ADMIN).with(TestAuth.asRole(1L, "STAFF"))
                        .contentType(APPLICATION_JSON).content(json(body("Nope", null))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(ADMIN + "/" + inactive.getSlug()).with(TestAuth.asRole(1L, "STAFF")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCategories_customer_returns403() throws Exception {
        mockMvc.perform(get(ADMIN).with(TestAuth.asRole(1L, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    // ---- helpers -----------------------------------------------------------

    private static Map<String, Object> body(String name, String parentSlug) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("parentSlug", parentSlug);
        return body;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
