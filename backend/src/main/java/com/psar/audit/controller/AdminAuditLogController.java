package com.psar.audit.controller;

import com.psar.audit.dto.response.AuditLogResponse;
import com.psar.audit.entity.AuditAction;
import com.psar.audit.service.AuditLogQueryService;
import com.psar.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Read-only: audit rows are insert-only, so there is no update or delete endpoint. */
@Tag(name = "Admin - audit logs")
@RestController
@RequestMapping("/api/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    @Operation(summary = "Audit log, newest first (max 100 per page)")
    @GetMapping
    public PageResponse<AuditLogResponse> list(
            @Parameter(description = "Actor's user publicId") @RequestParam(required = false) UUID user,
            @RequestParam(required = false) AuditAction action,
            @Parameter(description = "e.g. PRODUCT, USER, AUTH (case-insensitive)")
            @RequestParam(required = false) String entity,
            @Parameter(description = "Public id / slug / PO number of the record")
            @RequestParam(required = false) String entityId,
            @Parameter(description = "Inclusive, ISO-8601 instant, e.g. 2026-01-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Exclusive, ISO-8601 instant")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return auditLogQueryService.list(new AuditLogQueryService.Filter(user, action, entity, entityId, from, to),
                page, size);
    }

    @Operation(summary = "One audit row")
    @GetMapping("/{publicId}")
    public AuditLogResponse get(@PathVariable UUID publicId) {
        return auditLogQueryService.get(publicId);
    }
}
