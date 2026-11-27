package com.psar.audit.repository;

import com.psar.audit.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    Optional<AuditLog> findByPublicId(UUID publicId);

    List<AuditLog> findAllByEntityAndEntityIdOrderByIdAsc(String entity, String entityId);
}
