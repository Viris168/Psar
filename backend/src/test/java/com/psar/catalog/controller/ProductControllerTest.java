package com.psar.catalog.controller;

import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.entity.ProductImage;
import com.psar.catalog.repository.CategoryRepository;
import com.psar.catalog.repository.ProductImageRepository;
import com.psar.catalog.repository.ProductRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Storefront products (/api/public/products): no login, active items only, no cost price. */
class ProductControllerTest extends IntegrationTest {

    private static final String PUBLIC = "/api/public/products";

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Test
    void publicProducts_inactiveOrInInactiveCategory_hidden() throws Exception {
        Category category = fixtures.category("Visible");
        Product visible = fixtures.product(category, "Visible item", "2.00", 1, 0);
        Product inactive = fixtures.product(category, "Inactive item", "2.00", 1, 0);
        inactive.setActive(false);
        productRepository.save(inactive);
        Category hiddenCategory = fixtures.category("Hidden cat");
        Product inHiddenCategory = fixtures.product(hiddenCategory, "Hidden cat item", "2.00", 1, 0);
        hiddenCategory.setActive(false);
        categoryRepository.save(hiddenCategory);

        mockMvc.perform(get(PUBLIC).param("q", visible.getSku()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].slug", contains(visible.getSlug())));
        mockMvc.perform(get(PUBLIC).param("q", inactive.getSku()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(PUBLIC).param("q", inHiddenCategory.getSku()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(PUBLIC + "/" + inactive.getSlug())).andExpect(status().isNotFound());
        mockMvc.perform(get(PUBLIC + "/" + inHiddenCategory.getSlug())).andExpect(status().isNotFound());
        mockMvc.perform(get(PUBLIC).param("category", hiddenCategory.getSlug())).andExpect(status().isNotFound());
    }

    @Test
    void publicProduct_detail_hasNoCostPriceOrQuantity() throws Exception {
        Category parent = fixtures.category("Dairy");
        Category child = fixtures.category("Milk", parent);
        Product product = fixtures.product(child, "Fresh Milk 1L", "1.75", 5, 0);
        image(product, "https://cdn.test/second.webp", false, 0);
        image(product, "https://cdn.test/main.webp", true, 1);

        mockMvc.perform(get(PUBLIC + "/" + product.getSlug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(product.getPublicId().toString()))
                .andExpect(jsonPath("$.sellPrice").value(1.75))
                .andExpect(jsonPath("$.inStock").value(true))
                .andExpect(jsonPath("$.costPrice").doesNotExist())
                .andExpect(jsonPath("$.quantity").doesNotExist())
                .andExpect(jsonPath("$.supplier").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.images[*].url",
                        contains("https://cdn.test/main.webp", "https://cdn.test/second.webp")))
                .andExpect(jsonPath("$.images[0].main").value(true))
                .andExpect(jsonPath("$.breadcrumb[*].slug", contains(parent.getSlug(), child.getSlug())));
    }

    @Test
    void publicProducts_list_hasMainImageAndNoCostPrice() throws Exception {
        Category category = fixtures.category("With images");
        Product product = fixtures.product(category, "Pictured", "3.00", 0, 0);
        image(product, "https://cdn.test/pictured.webp", true, 0);

        mockMvc.perform(get(PUBLIC).param("category", category.getSlug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].mainImageUrl").value("https://cdn.test/pictured.webp"))
                .andExpect(jsonPath("$.content[0].inStock").value(false))
                .andExpect(jsonPath("$.content[0].category.slug").value(category.getSlug()))
                .andExpect(jsonPath("$.content[0].costPrice").doesNotExist())
                .andExpect(jsonPath("$.content[0].quantity").doesNotExist());
    }

    @Test
    void publicProducts_parentCategoryFilter_includesSubcategories() throws Exception {
        Category parent = fixtures.category("Food");
        Category child = fixtures.category("Noodles", parent);
        Product inParent = fixtures.product(parent, "Rice", "5.00", 1, 0);
        Product inChild = fixtures.product(child, "Instant noodles", "0.50", 1, 0);
        Product elsewhere = fixtures.product(fixtures.category("Elsewhere"), "Soap", "1.00", 1, 0);

        mockMvc.perform(get(PUBLIC).param("category", parent.getSlug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].slug", containsInAnyOrder(inParent.getSlug(), inChild.getSlug())));
        mockMvc.perform(get(PUBLIC).param("category", child.getSlug()))
                .andExpect(jsonPath("$.content[*].slug", contains(inChild.getSlug())));
        mockMvc.perform(get(PUBLIC + "/" + elsewhere.getSlug())).andExpect(status().isOk());
    }

    @Test
    void publicProducts_priceRangeAndSort_filtersAndOrders() throws Exception {
        Category category = fixtures.category("Prices");
        Product cheap = fixtures.product(category, "Banana", "1.00", 1, 0);
        Product middle = fixtures.product(category, "Apple", "2.50", 1, 0);
        Product dear = fixtures.product(category, "Cherry", "4.00", 1, 0);
        fixtures.product(category, "Durian", "9.99", 1, 0);

        mockMvc.perform(get(PUBLIC).param("category", category.getSlug())
                        .param("minPrice", "1.00").param("maxPrice", "4.00").param("sort", "price_desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].slug", contains(dear.getSlug(), middle.getSlug(), cheap.getSlug())));
        mockMvc.perform(get(PUBLIC).param("category", category.getSlug())
                        .param("maxPrice", "4.00").param("sort", "name"))
                .andExpect(jsonPath("$.content[*].slug", contains(middle.getSlug(), cheap.getSlug(), dear.getSlug())));
        mockMvc.perform(get(PUBLIC).param("category", category.getSlug())
                        .param("minPrice", "5").param("maxPrice", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publicProducts_inStockFilterAndNewestDefault_filtersAndOrders() throws Exception {
        Category category = fixtures.category("Stocked");
        Product older = fixtures.product(category, "Older", "1.00", 2, 0);
        Product soldOut = fixtures.product(category, "Sold out", "1.00", 0, 0);
        Product newer = fixtures.product(category, "Newer", "1.00", 3, 0);

        mockMvc.perform(get(PUBLIC).param("category", category.getSlug()).param("inStock", "true"))
                .andExpect(jsonPath("$.content[*].slug", contains(newer.getSlug(), older.getSlug())));
        mockMvc.perform(get(PUBLIC).param("category", category.getSlug()).param("inStock", "false"))
                .andExpect(jsonPath("$.content[*].slug", contains(soldOut.getSlug())));
    }

    @Test
    void publicProducts_searchByNameCaseInsensitive_matches() throws Exception {
        Category category = fixtures.category("Search");
        Product product = fixtures.product(category, "Kampot Pepper", "6.00", 1, 0);

        mockMvc.perform(get(PUBLIC).param("category", category.getSlug()).param("q", "kampot PEP"))
                .andExpect(jsonPath("$.content[*].slug", contains(product.getSlug())));
        mockMvc.perform(get(PUBLIC).param("category", category.getSlug()).param("q", "100%_"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void publicProducts_sizeOver100_cappedAt100() throws Exception {
        mockMvc.perform(get(PUBLIC).param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    private void image(Product product, String url, boolean main, int sortOrder) {
        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setImageUrl(url);
        image.setStorageKey("products/" + url.hashCode());
        image.setMain(main);
        image.setSortOrder(sortOrder);
        productImageRepository.save(image);
    }
}
