package com.psar.notification.listener;

import com.psar.catalog.dto.response.ProductStockInfo;
import com.psar.inventory.event.StockLowEvent;
import com.psar.inventory.service.StockService;
import com.psar.notification.entity.NotificationType;
import com.psar.notification.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;

/**
 * Low stock → in-app notification for every active ADMIN and MANAGER.
 * Runs after the stock change committed, in its own transaction: a failure here is logged and
 * never undoes the stock change.
 */
@Slf4j
@Component
public class LowStockNotificationListener {

    static final Set<String> RECIPIENT_ROLES = Set.of("ADMIN", "MANAGER");
    static final String LINK = "/admin/inventory/low-stock";
    private static final int TITLE_MAX = 150;

    private final StockService stockService;
    private final NotificationService notificationService;
    private final TransactionTemplate requiresNew;

    public LowStockNotificationListener(StockService stockService, NotificationService notificationService,
                                        PlatformTransactionManager transactionManager) {
        this.stockService = stockService;
        this.notificationService = notificationService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockLow(StockLowEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> notify(event.productId()));
        } catch (RuntimeException ex) {
            log.error("Low-stock notification failed for product {}", event.productId(), ex);
        }
    }

    private void notify(Long productId) {
        ProductStockInfo product = stockService.product(productId);
        if (!product.active() || product.quantity() > product.minStock()) {
            log.debug("Product {} is inactive or back above its minimum; no low-stock notification", productId);
            return;
        }
        String title = truncate("Low stock: " + product.name(), TITLE_MAX);
        String message = "%s (%s) has %d left (minimum %d).".formatted(
                product.name(), product.sku(), product.quantity(), product.minStock());
        notificationService.notifyRoles(RECIPIENT_ROLES, NotificationType.LOW_STOCK, title, message, LINK);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
