package com.psar.inventory.service;

import com.psar.catalog.entity.Product;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.inventory.dto.request.CreatePurchaseOrderRequest;
import com.psar.inventory.dto.request.PurchaseOrderItemRequest;
import com.psar.inventory.dto.response.PurchaseOrderDetailResponse;
import com.psar.inventory.entity.PurchaseOrderStatus;
import com.psar.inventory.entity.Supplier;
import com.psar.inventory.repository.StockMovementRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseOrderServiceTest extends IntegrationTest {

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private CatalogFixtures fixtures;

    @Test
    void receivePurchaseOrder_twiceConcurrently_stockAddedOnce() throws Exception {
        Supplier supplier = fixtures.supplier("Concurrent supplier");
        Product product = fixtures.product(fixtures.category("PO concurrency"), "Cooking oil", "2.50", 0, 0);
        Long managerId = fixtures.manager().getId();
        PurchaseOrderDetailResponse po = purchaseOrderService.create(new CreatePurchaseOrderRequest(
                supplier.getPublicId(), null,
                List.of(new PurchaseOrderItemRequest(product.getPublicId(), 12, new BigDecimal("1.80")))), managerId);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ErrorCode>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        purchaseOrderService.receive(po.poNo(), managerId);
                        return null;
                    } catch (ConflictException e) {
                        return e.getErrorCode();
                    }
                }));
            }
            start.countDown();
            List<ErrorCode> outcomes = new ArrayList<>();
            for (Future<ErrorCode> result : results) {
                outcomes.add(result.get());
            }
            assertThat(outcomes).containsExactlyInAnyOrder(null, ErrorCode.INVALID_STATUS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(fixtures.quantityOf(product)).isEqualTo(12);
        assertThat(stockMovementRepository.count((root, query, cb) ->
                cb.equal(root.get("productId"), product.getId()))).isEqualTo(1);
        assertThat(purchaseOrderService.get(po.poNo()).status()).isEqualTo(PurchaseOrderStatus.RECEIVED);
    }

    @Test
    void receivePurchaseOrders_sameProductsInOppositeOrderConcurrently_bothSucceed() throws Exception {
        Supplier supplier = fixtures.supplier("Opposite order supplier");
        var category = fixtures.category("PO lock order");
        Product rice = fixtures.product(category, "Rice", "1.00", 0, 0);
        Product salt = fixtures.product(category, "Salt", "1.00", 0, 0);
        Long managerId = fixtures.manager().getId();

        // a few rounds: without a fixed lock order, opposite item orders deadlock now and then
        for (int round = 0; round < 5; round++) {
            String riceThenSalt = purchaseOrderService.create(new CreatePurchaseOrderRequest(supplier.getPublicId(), null,
                    List.of(item(rice), item(salt))), managerId).poNo();
            String saltThenRice = purchaseOrderService.create(new CreatePurchaseOrderRequest(supplier.getPublicId(), null,
                    List.of(item(salt), item(rice))), managerId).poNo();

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<?>> results = new ArrayList<>();
                for (String poNo : List.of(riceThenSalt, saltThenRice)) {
                    results.add(pool.submit(() -> {
                        start.await();
                        purchaseOrderService.receive(poNo, managerId);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> result : results) {
                    result.get(); // throws if a receive failed (e.g. deadlock)
                }
            } finally {
                pool.shutdownNow();
            }
        }

        assertThat(fixtures.quantityOf(rice)).isEqualTo(10 * 3);
        assertThat(fixtures.quantityOf(salt)).isEqualTo(10 * 3);
    }

    private static PurchaseOrderItemRequest item(Product product) {
        return new PurchaseOrderItemRequest(product.getPublicId(), 3, new BigDecimal("0.50"));
    }
}
