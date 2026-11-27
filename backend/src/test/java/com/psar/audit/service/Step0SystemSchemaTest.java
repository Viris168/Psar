package com.psar.audit.service;

import com.psar.audit.entity.AuditAction;
import com.psar.audit.entity.AuditLog;
import com.psar.catalog.entity.Product;
import com.psar.common.security.AuthenticatedUser;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.event.StockLowEvent;
import com.psar.inventory.service.StockService;
import com.psar.notification.entity.NotificationType;
import com.psar.notification.service.NotificationService;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.SystemFixtures;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the Step 0 contract: audit rows commit with their change, notifications per role, StockLowEvent. */
@RecordApplicationEvents
class Step0SystemSchemaTest extends IntegrationTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private StockService stockService;

    @Autowired
    private SystemFixtures system;

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEvents events;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void record_inTransaction_savesActorFromSecurityContextAndJsonDetails() {
        User manager = system.user(Role.MANAGER);
        loginAs(manager);
        String entityId = UUID.randomUUID().toString();

        tx().executeWithoutResult(status -> auditService.record(AuditAction.UPDATE, "PRODUCT", entityId,
                Map.of("sellPrice", Map.of("from", "1.50", "to", "1.75"))));

        List<AuditLog> rows = system.auditRows("PRODUCT", entityId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getUserId()).isEqualTo(manager.getId());
        assertThat(rows.getFirst().getAction()).isEqualTo(AuditAction.UPDATE);
        assertThat(rows.getFirst().getDetails()).isEqualTo(Map.of("sellPrice", Map.of("from", "1.50", "to", "1.75")));
    }

    @Test
    void record_withoutTransaction_isRejected() {
        assertThatThrownBy(() -> auditService.record(AuditAction.CREATE, "PRODUCT", "x", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void record_callerRollsBack_noAuditRow() {
        String entityId = UUID.randomUUID().toString();

        tx().executeWithoutResult(status -> {
            auditService.record(AuditAction.CREATE, "PRODUCT", entityId, Map.of("name", "Rolled back"));
            status.setRollbackOnly();
        });

        assertThat(system.auditRows("PRODUCT", entityId)).isEmpty();
    }

    @Test
    void recordLoginFailed_callerRollsBack_rowStaysWithoutPassword() {
        String email = "Nobody-" + UUID.randomUUID().toString().substring(0, 8) + "@Test.Local";

        tx().executeWithoutResult(status -> {
            auditService.recordLoginFailed(email, null, "BAD_CREDENTIALS");
            status.setRollbackOnly();
        });

        List<AuditLog> rows = system.auditRows(AuditService.AUTH, null).stream()
                .filter(row -> email.toLowerCase().equals(row.getDetails().get("email")))
                .toList();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getAction()).isEqualTo(AuditAction.LOGIN_FAILED);
            assertThat(row.getUserId()).isNull();
            assertThat(row.getDetails()).containsOnlyKeys("email", "reason");
        });
    }

    @Test
    void notifyRoles_activeManagersAndAdminsOnly_oneRowEach() {
        User manager = system.user(Role.MANAGER);
        User admin = system.user(Role.ADMIN);
        User staff = system.user(Role.STAFF);
        User inactiveManager = system.user(Role.MANAGER, false);

        notificationService.notifyRoles(Set.of("ADMIN", "MANAGER"), NotificationType.LOW_STOCK,
                "Low stock", "Rice has 2 left", "/admin/inventory/low-stock");

        assertThat(system.notifications(manager, NotificationType.LOW_STOCK)).singleElement()
                .satisfies(n -> {
                    assertThat(n.isRead()).isFalse();
                    assertThat(n.getLink()).isEqualTo("/admin/inventory/low-stock");
                });
        assertThat(system.notifications(admin, NotificationType.LOW_STOCK)).hasSize(1);
        assertThat(system.notifications(staff, NotificationType.LOW_STOCK)).isEmpty();
        assertThat(system.notifications(inactiveManager, NotificationType.LOW_STOCK)).isEmpty();
    }

    @Test
    void notifyUsers_duplicateIds_oneRowPerUser() {
        User customer = system.user(Role.CUSTOMER);

        int created = notificationService.notifyUsers(List.of(customer.getId(), customer.getId()),
                NotificationType.LOW_STOCK, "Title", "Message", null);

        assertThat(created).isEqualTo(1);
        assertThat(system.notifications(customer, NotificationType.LOW_STOCK)).hasSize(1);
    }

    @Test
    void stockChanges_crossingMinimumOnly_publishStockLowEventOnce() {
        Product product = fixtures.product(fixtures.category("Low stock events"), "Fish sauce", "1.00", 12, 10);
        Long managerId = fixtures.manager().getId();

        stockService.adjust(product.getPublicId(), -1, MovementReason.DAMAGE, null, managerId); // 12 -> 11
        assertThat(lowStockEvents(product)).isZero();
        stockService.adjust(product.getPublicId(), -2, MovementReason.DAMAGE, null, managerId); // 11 -> 9: crosses
        assertThat(lowStockEvents(product)).isEqualTo(1);
        tx().executeWithoutResult(status ->
                stockService.remove(product.getId(), 1, MovementReason.CORRECTION, null, managerId)); // 9 -> 8
        stockService.adjust(product.getPublicId(), 5, MovementReason.RETURN, null, managerId); // 8 -> 13
        assertThat(lowStockEvents(product)).isEqualTo(1);
        tx().executeWithoutResult(status ->
                stockService.remove(product.getId(), 3, MovementReason.CORRECTION, null, managerId)); // 13 -> 10: crosses
        assertThat(lowStockEvents(product)).isEqualTo(2);
    }

    private long lowStockEvents(Product product) {
        return events.stream(StockLowEvent.class).filter(e -> e.productId().equals(product.getId())).count();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static void loginAs(User user) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getPublicId(), user.getEmail(),
                user.getRole().name());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of()));
    }
}
