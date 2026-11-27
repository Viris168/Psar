package com.psar.inventory.service;

import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.entity.ProductImage;
import com.psar.catalog.repository.ProductImageRepository;
import com.psar.common.exception.BusinessRuleException;
import com.psar.inventory.entity.PurchaseOrder;
import com.psar.inventory.entity.PurchaseOrderItem;
import com.psar.inventory.entity.Supplier;
import com.psar.inventory.repository.PurchaseOrderRepository;
import com.psar.inventory.repository.SupplierRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the Step 0 schema and services: PO numbers, one main image, SupplierService lookups. */
class Step0InventorySchemaTest extends IntegrationTest {

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private SupplierService supplierService;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Test
    void savePurchaseOrder_generatesSequentialPoNumberAndSavesItems() {
        Supplier supplier = fixtures.supplier("Lucky Wholesale");
        Product product = fixtures.product(fixtures.category("PO tests"), "Noodles", "0.40", 0, 0);

        PurchaseOrder first = purchaseOrderRepository.save(purchaseOrder(supplier, product));
        PurchaseOrder second = purchaseOrderRepository.save(purchaseOrder(supplier, product));

        assertThat(first.getPoNo()).matches("PO-\\d{6}");
        assertThat(Integer.parseInt(second.getPoNo().substring(3)))
                .isGreaterThan(Integer.parseInt(first.getPoNo().substring(3)));
        assertThat(purchaseOrderRepository.findByPoNo(first.getPoNo())).isPresent();
    }

    @Test
    void saveSecondMainImage_sameProduct_rejectedByDatabase() {
        Category category = fixtures.category("Image tests");
        Product product = fixtures.product(category, "Shampoo", "4.00", 0, 0);
        productImageRepository.saveAndFlush(image(product, true));

        assertThatThrownBy(() -> productImageRepository.saveAndFlush(image(product, true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requireActiveId_activeSupplier_returnsId() {
        Supplier supplier = fixtures.supplier("Active Supplier");

        assertThat(supplierService.requireActiveId(supplier.getPublicId())).isEqualTo(supplier.getId());
    }

    @Test
    void requireActiveId_inactiveOrMissingSupplier_throws422() {
        Supplier supplier = fixtures.supplier("Closed Supplier");
        supplier.setActive(false);
        supplierRepository.save(supplier);

        assertThatThrownBy(() -> supplierService.requireActiveId(supplier.getPublicId()))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> supplierService.requireActiveId(UUID.randomUUID()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void summaries_knownAndUnknownIds_returnsKnownOnly() {
        Supplier supplier = fixtures.supplier("Summary Supplier");

        var summaries = supplierService.summaries(List.of(supplier.getId(), Long.MAX_VALUE));

        assertThat(summaries).containsOnlyKeys(supplier.getId());
        assertThat(summaries.get(supplier.getId()).name()).isEqualTo("Summary Supplier");
        assertThat(summaries.get(supplier.getId()).publicId()).isEqualTo(supplier.getPublicId());
    }

    private static PurchaseOrder purchaseOrder(Supplier supplier, Product product) {
        PurchaseOrder po = new PurchaseOrder();
        po.setSupplier(supplier);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setProductId(product.getId());
        item.setQuantity(10);
        item.setUnitCost(new BigDecimal("0.25"));
        po.addItem(item);
        po.setTotalAmount(item.lineTotal());
        return po;
    }

    private static ProductImage image(Product product, boolean main) {
        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setImageUrl("http://localhost:9000/psar/products/" + UUID.randomUUID() + ".webp");
        image.setStorageKey("products/" + UUID.randomUUID() + ".webp");
        image.setMain(main);
        return image;
    }
}
