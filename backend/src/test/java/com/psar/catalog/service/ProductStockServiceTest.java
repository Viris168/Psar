package com.psar.catalog.service;

import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.repository.ProductRepository;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.common.exception.NotFoundException;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductStockServiceTest extends IntegrationTest {

    @Autowired
    private ProductStockService productStockService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private TransactionTemplate tx;

    private Category category;

    @BeforeEach
    void setUp() {
        category = fixtures.category("Stock tests");
    }

    @Test
    void increase_inTransaction_returnsNewQuantity() {
        Product product = fixtures.product(category, "Rice 5kg", "6.50", 4, 0);

        int after = tx.execute(status -> productStockService.increase(product.getId(), 6));

        assertThat(after).isEqualTo(10);
        assertThat(fixtures.quantityOf(product)).isEqualTo(10);
    }

    @Test
    void decrease_notEnoughStock_throwsInsufficientStockAndKeepsQuantity() {
        Product product = fixtures.product(category, "Fish sauce", "1.20", 2, 0);

        assertThatThrownBy(() -> tx.execute(status -> productStockService.decrease(product.getId(), 3)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
        assertThat(fixtures.quantityOf(product)).isEqualTo(2);
    }

    @Test
    void decrease_unknownProduct_throwsNotFound() {
        assertThatThrownBy(() -> tx.execute(status -> productStockService.decrease(Long.MAX_VALUE, 1)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void increase_withoutTransaction_isRejected() {
        Product product = fixtures.product(category, "Soap", "0.80", 1, 0);

        // StockService must call it inside its own transaction, together with the movement row
        assertThatThrownBy(() -> productStockService.increase(product.getId(), 1))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void decrease_tenConcurrentBuyersThreeLeft_exactlyThreeSucceed() throws Exception {
        Product product = fixtures.product(category, "Last mangoes", "2.00", 3, 0);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        tx.execute(status -> productStockService.decrease(product.getId(), 1));
                        return true;
                    } catch (ConflictException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            long succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(fixtures.quantityOf(product)).isZero();
    }

    @Test
    void saveStaleProduct_afterStockChange_doesNotOverwriteQuantity() {
        Product stale = productRepository.findById(fixtures.product(category, "Coffee", "3.00", 5, 0).getId())
                .orElseThrow();
        tx.executeWithoutResult(status -> productStockService.increase(stale.getId(), 20));

        stale.setName("Coffee 200g");   // admin edits the product loaded before the stock change
        productRepository.save(stale);

        assertThat(fixtures.quantityOf(stale)).isEqualTo(25);
    }

    @Test
    void lowStock_returnsOnlyActiveProductsAtOrBelowMinimum() {
        Product low = fixtures.product(category, "Eggs", "3.00", 2, 5);
        Product exactlyMin = fixtures.product(category, "Bread", "1.00", 5, 5);
        Product enough = fixtures.product(category, "Water", "0.50", 50, 5);
        Product inactive = fixtures.product(category, "Old stock", "1.00", 0, 5);
        inactive.setActive(false);
        productRepository.save(inactive);

        List<UUID> ids = productStockService.lowStock(PageRequest.of(0, 1000)).map(p -> p.publicId()).getContent();

        assertThat(ids).contains(low.getPublicId(), exactlyMin.getPublicId())
                .doesNotContain(enough.getPublicId(), inactive.getPublicId());
    }

    @Test
    void require_unknownPublicId_throwsNotFound() {
        assertThatThrownBy(() -> productStockService.require(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }
}
