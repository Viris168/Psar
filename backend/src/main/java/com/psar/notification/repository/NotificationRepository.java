package com.psar.notification.repository;

import com.psar.notification.entity.Notification;
import com.psar.notification.entity.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Always scoped to the owner: another user's notification is "not found". */
    Optional<Notification> findByPublicIdAndUserId(UUID publicId, Long userId);

    Page<Notification> findAllByUserId(Long userId, Pageable pageable);

    Page<Notification> findAllByUserIdAndReadFalse(Long userId, Pageable pageable);

    long countByUserIdAndReadFalse(Long userId);

    List<Notification> findAllByUserIdAndTypeOrderByIdAsc(Long userId, NotificationType type);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.read = true, n.readAt = :now where n.userId = :userId and n.read = false")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
