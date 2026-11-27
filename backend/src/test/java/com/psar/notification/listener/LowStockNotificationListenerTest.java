package com.psar.notification.listener;

import com.psar.catalog.entity.Product;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.service.StockService;
import com.psar.notification.entity.Notification;
import com.psar.notification.entity.NotificationType;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.SystemFixtures;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LowStockNotificationListenerTest extends IntegrationTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private SystemFixtures system;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void adjustStock_crossesMinimum_notifiesActiveManagersAndAdmins() {
        User manager = system.user(Role.MANAGER);
        User admin = system.user(Role.ADMIN);
        User staff = system.user(Role.STAFF);
        User customer = system.user(Role.CUSTOMER);
        User inactiveManager = system.user(Role.MANAGER, false);
        Product product = fixtures.product(fixtures.category("Low stock notify"), "Jasmine rice", "2.00", 12, 10);

        stockService.adjust(product.getPublicId(), -5, MovementReason.DAMAGE, null, manager.getId()); // 12 -> 7

        assertThat(lowStockFor(manager, product)).singleElement().satisfies(n -> {
            assertThat(n.getTitle()).isEqualTo("Low stock: Jasmine rice");
            assertThat(n.getMessage()).isEqualTo(
                    "Jasmine rice (" + product.getSku() + ") has 7 left (minimum 10).");
            assertThat(n.getLink()).isEqualTo("/admin/inventory/low-stock");
            assertThat(n.isRead()).isFalse();
        });
        assertThat(lowStockFor(admin, product)).hasSize(1);
        assertThat(lowStockFor(staff, product)).isEmpty();
        assertThat(lowStockFor(customer, product)).isEmpty();
        assertThat(lowStockFor(inactiveManager, product)).isEmpty();
    }

    @Test
    void adjustStock_alreadyBelowMinimum_noNewNotification() {
        User manager = system.user(Role.MANAGER);
        Product product = fixtures.product(fixtures.category("Low stock again"), "Soy sauce", "1.00", 12, 10);

        stockService.adjust(product.getPublicId(), -3, MovementReason.DAMAGE, null, manager.getId()); // 12 -> 9
        stockService.adjust(product.getPublicId(), -2, MovementReason.DAMAGE, null, manager.getId()); // 9 -> 7

        assertThat(lowStockFor(manager, product)).hasSize(1);
    }

    @Test
    void adjustStock_rolledBack_noNotification() {
        User manager = system.user(Role.MANAGER);
        Product product = fixtures.product(fixtures.category("Low stock rollback"), "Palm sugar", "3.00", 12, 10);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            stockService.adjust(product.getPublicId(), -5, MovementReason.DAMAGE, null, manager.getId());
            status.setRollbackOnly();
        });

        assertThat(fixtures.quantityOf(product)).isEqualTo(12);
        assertThat(lowStockFor(manager, product)).isEmpty();
    }

    @Test
    void onStockLow_backAboveMinimumBeforeListenerRuns_noNotification() {
        User manager = system.user(Role.MANAGER);
        Product product = fixtures.product(fixtures.category("Low stock refilled"), "Kampot pepper", "5.00", 12, 10);

        // crossing and refill in one transaction: by the time the listener reloads, stock is fine again
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            stockService.adjust(product.getPublicId(), -5, MovementReason.DAMAGE, null, manager.getId());
            stockService.adjust(product.getPublicId(), 5, MovementReason.RETURN, null, manager.getId());
        });

        assertThat(lowStockFor(manager, product)).isEmpty();
    }

    private List<Notification> lowStockFor(User user, Product product) {
        return system.notifications(user, NotificationType.LOW_STOCK).stream()
                .filter(n -> n.getMessage().contains(product.getSku()))
                .toList();
    }
}
