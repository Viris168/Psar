package com.psar.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Insert-only: no setters, and Hibernate never issues an UPDATE for it. Build one with {@link #of}. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Immutable
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId = UUID.randomUUID();

    /** Internal user id of the actor; null for system work or an anonymous failed login. */
    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20, updatable = false)
    private AuditAction action;

    @Column(name = "entity", nullable = false, length = 50, updatable = false)
    private String entity;

    @Column(name = "entity_id", length = 100, updatable = false)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> details;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public static AuditLog of(Long userId, AuditAction action, String entity, String entityId,
                              Map<String, Object> details, String ipAddress) {
        AuditLog log = new AuditLog();
        log.userId = userId;
        log.action = action;
        log.entity = entity;
        log.entityId = entityId;
        log.details = details == null || details.isEmpty() ? null : details;
        log.ipAddress = ipAddress;
        return log;
    }
}
