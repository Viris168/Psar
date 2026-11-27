package com.psar.audit.controller;

import com.psar.audit.entity.AuditAction;
import com.psar.audit.service.AuditService;
import com.psar.support.IntegrationTest;
import com.psar.support.SystemFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/admin/audit-logs: ADMIN only, newest first, filters. */
class AdminAuditLogControllerTest extends IntegrationTest {

    private static final String URL = "/api/admin/audit-logs";

    @Autowired
    private SystemFixtures system;

    @Autowired
    private AuditService auditService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void auditLogs_manager_returns403() throws Exception {
        mockMvc.perform(get(URL).with(TestAuth.as(system.user(Role.MANAGER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/" + UUID.randomUUID()).with(TestAuth.asRole(1L, "STAFF")))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditLogs_noLogin_returns401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void auditLogs_filters_byEntityAndDate() throws Exception {
        User actor = system.user(Role.MANAGER);
        String entityId = UUID.randomUUID().toString();
        recordAs(actor, AuditAction.CREATE, "PRODUCT", entityId);
        recordAs(actor, AuditAction.UPDATE, "PRODUCT", entityId);
        recordAs(actor, AuditAction.CREATE, "SUPPLIER", entityId);
        Instant now = Instant.now();

        mockMvc.perform(get(URL).with(admin()).param("entity", "product").param("entityId", entityId)
                        .param("from", now.minus(1, ChronoUnit.HOURS).toString())
                        .param("to", now.plus(1, ChronoUnit.HOURS).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                // newest first
                .andExpect(jsonPath("$.content[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.content[1].action").value("CREATE"))
                .andExpect(jsonPath("$.content[0].entity").value("PRODUCT"))
                .andExpect(jsonPath("$.content[0].actor.publicId").value(actor.getPublicId().toString()))
                .andExpect(jsonPath("$.content[0].actor.email").value(actor.getEmail()))
                .andExpect(jsonPath("$.content[0].actor.id").doesNotExist())
                .andExpect(jsonPath("$.content[0].userId").doesNotExist())
                .andExpect(jsonPath("$.content[0].id").doesNotExist());

        // a window that ended before the rows were written
        mockMvc.perform(get(URL).with(admin()).param("entity", "PRODUCT").param("entityId", entityId)
                        .param("to", now.minus(1, ChronoUnit.HOURS).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void auditLogs_filters_byUserAndAction() throws Exception {
        User actor = system.user(Role.MANAGER);
        User other = system.user(Role.MANAGER);
        String entityId = UUID.randomUUID().toString();
        recordAs(actor, AuditAction.DELETE, "CATEGORY", entityId);
        recordAs(other, AuditAction.DELETE, "CATEGORY", entityId);

        mockMvc.perform(get(URL).with(admin()).param("user", actor.getPublicId().toString())
                        .param("action", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].entityId").value(entityId));

        mockMvc.perform(get(URL).with(admin()).param("user", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void auditLogs_sizeOver100_cappedAt100() throws Exception {
        mockMvc.perform(get(URL).with(admin()).param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void auditLogs_fromAfterTo_returns400() throws Exception {
        mockMvc.perform(get(URL).with(admin()).param("from", "2026-02-01T00:00:00Z").param("to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAuditLog_byPublicId_returnsRowWithDetails() throws Exception {
        User actor = system.user(Role.ADMIN);
        String entityId = UUID.randomUUID().toString();
        recordAs(actor, AuditAction.UPDATE, "PRODUCT", entityId);
        UUID publicId = system.auditRows("PRODUCT", entityId).getFirst().getPublicId();

        mockMvc.perform(get(URL + "/" + publicId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(publicId.toString()))
                .andExpect(jsonPath("$.details.name.from").value("Old"))
                .andExpect(jsonPath("$.details.name.to").value("New"))
                .andExpect(jsonPath("$.actor.publicId").value(actor.getPublicId().toString()));

        mockMvc.perform(get(URL + "/" + UUID.randomUUID()).with(admin()))
                .andExpect(status().isNotFound());
    }

    private void recordAs(User user, AuditAction action, String entity, String entityId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                auditService.recordAs(user.getId(), action, entity, entityId,
                        Map.of("name", Map.of("from", "Old", "to", "New"))));
    }

    private static RequestPostProcessor admin() {
        return TestAuth.asRole(1L, "ADMIN");
    }
}
