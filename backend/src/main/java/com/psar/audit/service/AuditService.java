package com.psar.audit.service;

import com.psar.audit.entity.AuditAction;
import com.psar.audit.entity.AuditLog;
import com.psar.audit.repository.AuditLogRepository;
import com.psar.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes audit_logs rows. Other modules call {@link #record} from their own write transaction, so the row
 * commits or rolls back with the change it describes. Never put passwords, password hashes, tokens or
 * storage keys in {@code details}.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    /** Entity name for login rows. */
    public static final String AUTH = "AUTH";

    private final AuditLogRepository auditLogRepository;

    /**
     * Records an action by the logged-in user (null outside a request, e.g. a scheduled job).
     * Must run inside the caller's transaction.
     *
     * @param entity   upper-case name, e.g. {@code PRODUCT}
     * @param entityId the public identifier (UUID, slug, PO number), never the internal id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, String entity, String entityId, Map<String, Object> details) {
        save(currentUserId(), action, entity, entityId, details);
    }

    // Other modules can't use audit.entity (ArchUnit), so they call these instead of passing an AuditAction.

    /** A CREATE row by the logged-in user; {@code details} = the main fields. Runs in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCreate(String entity, String entityId, Map<String, Object> details) {
        save(currentUserId(), AuditAction.CREATE, entity, entityId, details);
    }

    /** A DELETE row by the logged-in user; {@code details} = name / SKU so it still reads well. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDelete(String entity, String entityId, Map<String, Object> details) {
        save(currentUserId(), AuditAction.DELETE, entity, entityId, details);
    }

    /** A successful login ({@link #AUTH}); the user isn't in the security context yet. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordLogin(Long userId, String userPublicId, Map<String, Object> details) {
        save(userId, AuditAction.LOGIN, AUTH, userPublicId, details);
    }

    /**
     * An UPDATE row with only the fields that differ between the two snapshots ({@link AuditChanges});
     * nothing changed → no row. Must run inside the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordChanges(String entity, String entityId, AuditFields before, AuditFields after) {
        Map<String, Object> changes = AuditChanges.between(before, after);
        if (!changes.isEmpty()) {
            save(currentUserId(), AuditAction.UPDATE, entity, entityId, changes);
        }
    }

    /** Like {@link #record}, for an actor who isn't in the security context yet (a successful login). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAs(Long userId, AuditAction action, String entity, String entityId,
                         Map<String, Object> details) {
        save(userId, action, entity, entityId, details);
    }

    /**
     * A failed login, in its own transaction: the login itself rolls back. Stores the email (as typed,
     * lowercased) and the user if the email exists; never the password.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailed(String email, Long userIdIfKnown, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("email", email == null ? null : email.trim().toLowerCase());
        details.put("reason", reason);
        save(userIdIfKnown, AuditAction.LOGIN_FAILED, AUTH, null, details);
    }

    private void save(Long userId, AuditAction action, String entity, String entityId, Map<String, Object> details) {
        auditLogRepository.save(AuditLog.of(userId, action, entity, entityId, details, currentIp()));
    }

    private static Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? user.userId() : null;
    }

    /** The client's address as the server sees it (behind a proxy, configure forwarded headers). */
    private static String currentIp() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? truncate(attributes.getRequest()) : null;
    }

    private static String truncate(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip != null && ip.length() > 45 ? ip.substring(0, 45) : ip;
    }
}
