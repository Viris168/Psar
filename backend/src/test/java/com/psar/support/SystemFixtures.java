package com.psar.support;

import com.psar.audit.entity.AuditLog;
import com.psar.audit.repository.AuditLogRepository;
import com.psar.notification.entity.Notification;
import com.psar.notification.entity.NotificationType;
import com.psar.notification.repository.NotificationRepository;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import com.psar.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;

import java.util.List;
import java.util.UUID;

/**
 * Users with a role, and read helpers for audit rows and notifications.
 * Available in every IntegrationTest: {@code @Autowired SystemFixtures system;}
 */
@TestComponent
@RequiredArgsConstructor
public class SystemFixtures {

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final NotificationRepository notificationRepository;

    /** An active user with this role and a unique email (password not usable for login). */
    public User user(Role role) {
        return user(role, true);
    }

    public User user(Role role, boolean active) {
        User user = new User();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local");
        user.setPasswordHash("not-used");
        user.setRole(role);
        user.setActive(active);
        return userRepository.save(user);
    }

    /** Audit rows for one record, oldest first. */
    public List<AuditLog> auditRows(String entity, String entityId) {
        return auditLogRepository.findAllByEntityAndEntityIdOrderByIdAsc(entity, entityId);
    }

    /** A user's notifications of one type, oldest first. */
    public List<Notification> notifications(User user, NotificationType type) {
        return notificationRepository.findAllByUserIdAndTypeOrderByIdAsc(user.getId(), type);
    }
}
