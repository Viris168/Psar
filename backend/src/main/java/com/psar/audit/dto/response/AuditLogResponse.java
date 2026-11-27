package com.psar.audit.dto.response;

import com.psar.audit.entity.AuditAction;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One audit row. {@code actor} is null for system work or an anonymous failed login; {@code entityId} is the
 * record's public id (UUID, slug, PO number; delivery companies still use their id).
 */
public record AuditLogResponse(
        UUID publicId,
        AuditAction action,
        String entity,
        String entityId,
        Map<String, Object> details,
        String ipAddress,
        AuditActorResponse actor,
        Instant createdAt
) {
}
