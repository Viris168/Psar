package com.psar.inventory.service;

import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.common.exception.BadRequestException;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.entity.MovementType;
import com.psar.inventory.entity.StockMovement;
import com.psar.inventory.repository.StockMovementRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockServiceTest extends IntegrationTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private CatalogFixtures fixtures;

    private Category category;
    private Long managerId;

    @BeforeEach
    void setUp() {
        category = fixtures.category("Stock service tests");
        managerId = fixtures.manager().getId();
    }

    @Test
    void receive_positiveQuantity_addsStockAndWritesInMovement() {
        Product product = fixtures.product(category, "Rice", "6.50", 4, 0);

        stockService.receive(product.getId(), 6, MovementReason.PURCHASE, null, managerId);

        assertThat(fixtures.quantityOf(product)).isEqualTo(10);
        List<StockMovement> movements = movementsOf(product);
        assertThat(movements).singleElement().satisfies(m -> {
            assertThat(m.getType()).isEqualTo(MovementType.IN);
            assertThat(m.getQuantity()).isEqualTo(6);
            assertThat(m.getBalanceAfter()).isEqualTo(10);
            assertThat(m.getCreatedBy()).isEqualTo(managerId);
        });
    }

    @Test
    void remove_notEnoughStock_throws409AndWritesNoMovement() {
        Product product = fixtures.product(category, "Fish sauce", "1.20", 2, 0);

        assertThatThrownBy(() -> stockService.remove(product.getId(), 3, MovementReason.RESERVE, 99L, null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
        assertThat(fixtures.quantityOf(product)).isEqualTo(2);
        assertThat(movementsOf(product)).isEmpty();
    }

    @Test
    void remove_tenConcurrentBuyersThreeLeft_exactlyThreeSucceed() throws Exception {
        Product product = fixtures.product(category, "Last mangoes", "2.00", 3, 0);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<ErrorCode>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                long orderId = i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        stockService.remove(product.getId(), 1, MovementReason.RESERVE, orderId, null);
                        return null;
                    } catch (ConflictException e) {
                        return e.getErrorCode();
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            int insufficient = 0;
            for (Future<ErrorCode> result : results) {
                ErrorCode error = result.get();
                if (error == null) {
                    succeeded++;
                } else if (error == ErrorCode.INSUFFICIENT_STOCK) {
                    insufficient++;
                }
            }
            assertThat(succeeded).isEqualTo(3);
            assertThat(insufficient).isEqualTo(7);
        } finally {
            pool.shutdownNow();
        }
        assertThat(fixtures.quantityOf(product)).isZero();
        List<StockMovement> movements = movementsOf(product);
        assertThat(movements).hasSize(3)
                .allSatisfy(m -> {
                    assertThat(m.getType()).isEqualTo(MovementType.OUT);
                    assertThat(m.getQuantity()).isEqualTo(-1);
                });
        assertThat(movements).extracting(StockMovement::getBalanceAfter).containsExactlyInAnyOrder(2, 1, 0);
    }

    @Test
    void adjust_purchaseReason_returns400() {
        Product product = fixtures.product(category, "Soap", "0.80", 5, 0);

        assertThatThrownBy(() -> stockService.adjust(product.getPublicId(), 3, MovementReason.PURCHASE, null,
                managerId)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> stockService.adjust(product.getPublicId(), 0, MovementReason.CORRECTION, null,
                managerId)).isInstanceOf(BadRequestException.class);
        assertThat(movementsOf(product)).isEmpty();
    }

    private List<StockMovement> movementsOf(Product product) {
        return stockMovementRepository.findAll((root, query, cb) ->
                cb.equal(root.get("productId"), product.getId()));
    }
}
