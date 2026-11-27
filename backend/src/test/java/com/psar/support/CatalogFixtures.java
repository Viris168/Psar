package com.psar.support;

import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.repository.CategoryRepository;
import com.psar.catalog.repository.ProductRepository;
import com.psar.inventory.entity.Supplier;
import com.psar.inventory.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Saves catalog/inventory rows for tests, with unique names, slugs and SKUs.
 * Available in every IntegrationTest: {@code @Autowired CatalogFixtures fixtures;}
 * <p>
 * Test setup only: real code changes stock through StockService, which writes a movement row.
 * {@link #product} sets the starting quantity directly, without a movement.
 */
@TestComponent
@RequiredArgsConstructor
public class CatalogFixtures {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;

    public Category category(String name) {
        return category(name, null);
    }

    public Category category(String name, Category parent) {
        Category category = new Category();
        category.setName(name);
        category.setSlug(slug(name));
        category.setParent(parent);
        return categoryRepository.save(category);
    }

    public Supplier supplier(String name) {
        Supplier supplier = new Supplier();
        supplier.setName(name);
        return supplierRepository.save(supplier);
    }

    @Transactional
    public Product product(Category category, String name, String sellPrice, int quantity, int minStock) {
        String unique = shortId();
        Product product = new Product();
        product.setSku("SKU-" + unique.toUpperCase());
        product.setName(name);
        product.setSlug(slug(name) + "-" + unique);
        product.setCategory(category);
        product.setSellPrice(new BigDecimal(sellPrice));
        product.setCostPrice(new BigDecimal(sellPrice).divide(BigDecimal.TWO).setScale(2, RoundingMode.HALF_UP));
        product.setMinStock(minStock);
        product = productRepository.save(product);
        if (quantity > 0) {
            productRepository.increaseQuantity(product.getId(), quantity);
        }
        return productRepository.findById(product.getId()).orElseThrow();
    }

    /** Current quantity straight from the DB. */
    public int quantityOf(Product product) {
        return productRepository.findQuantityById(product.getId()).orElseThrow();
    }

    private static String slug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "") + "-" + shortId();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
