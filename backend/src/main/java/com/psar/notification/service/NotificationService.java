package com.psar.notification.service;

import com.psar.common.exception.NotFoundException;
import com.psar.common.web.PageResponse;
import com.psar.notification.dto.response.NotificationResponse;
import com.psar.notification.dto.response.ReadAllResponse;
import com.psar.notification.dto.response.UnreadCountResponse;
import com.psar.notification.entity.Notification;
import com.psar.notification.entity.NotificationType;
import com.psar.notification.mapper.NotificationMapper;
import com.psar.notification.repository.NotificationRepository;
import com.psar.user.service.UserDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-app notifications: one row per recipient. Other modules call {@link #notifyUsers} / {@link #notifyRoles}
 * (from an AFTER_COMMIT listener, so a notification never rolls back the business change).
 * The inbox methods only ever see the current user's rows: someone else's notification is a 404.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final int MAX_PAGE_SIZE = 100;
    /** Always newest first; the client's sort parameter is ignored. */
    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final NotificationRepository notificationRepository;
    private final UserDirectoryService userDirectoryService;
    private final NotificationMapper notificationMapper;

    // ---- writing (called by other modules) ---------------------------------------------------

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

    // ---- my inbox ----------------------------------------------------------------------------

    /** My notifications, newest first; {@code unreadOnly} keeps only unread ones. */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> listMine(Long userId, boolean unreadOnly, Pageable pageable) {
        Pageable safe = PageRequest.of(pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_PAGE_SIZE), LIST_SORT);
        Page<Notification> page = unreadOnly
                ? notificationRepository.findAllByUserIdAndReadFalse(userId, safe)
                : notificationRepository.findAllByUserId(userId, safe);
        return PageResponse.from(page.map(notificationMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(Long userId) {
        return new UnreadCountResponse(notificationRepository.countByUserIdAndReadFalse(userId));
    }

    /** Marks one of my notifications read (already read: no change). Not mine or missing → 404. */
    @Transactional
    public NotificationResponse markRead(Long userId, UUID publicId) {
        Notification notification = notificationRepository.findByPublicIdAndUserId(publicId, userId)
                .orElseThrow(() -> NotFoundException.of("Notification"));
        notification.markRead(Instant.now());
        return notificationMapper.toResponse(notification);
    }

    /** Marks all my unread notifications read; returns how many changed. */
    @Transactional
    public ReadAllResponse markAllRead(Long userId) {
        return new ReadAllResponse(notificationRepository.markAllRead(userId, Instant.now()));
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
