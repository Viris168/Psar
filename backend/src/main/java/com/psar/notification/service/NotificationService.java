package com.psar.notification.service;

import com.psar.notification.entity.Notification;
import com.psar.notification.entity.NotificationType;
import com.psar.notification.repository.NotificationRepository;
import com.psar.user.service.UserDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * In-app notifications: one row per recipient. Other modules call {@link #notifyUsers} / {@link #notifyRoles}
 * (from an AFTER_COMMIT listener, so a notification never rolls back the business change).
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserDirectoryService userDirectoryService;

    /** One APP notification per distinct user id. Returns how many were created. */
    @Transactional
    public int notifyUsers(Collection<Long> userIds, NotificationType type, String title, String message,
                           String link) {
        List<Notification> rows = new LinkedHashSet<>(userIds).stream()
                .map(userId -> build(userId, type, title, message, link))
                .toList();
        notificationRepository.saveAll(rows);
        return rows.size();
    }

    /** Every <b>active</b> user with one of these roles ({@code "ADMIN"}, {@code "MANAGER"}, …). */
    @Transactional
    public int notifyRoles(Set<String> roles, NotificationType type, String title, String message, String link) {
        return notifyUsers(userDirectoryService.activeUserIdsWithRoles(roles), type, title, message, link);
    }

    private static Notification build(Long userId, NotificationType type, String title, String message,
                                      String link) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setLink(link);
        return notification;
    }
}
