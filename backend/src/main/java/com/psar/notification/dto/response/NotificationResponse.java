package com.psar.notification.dto.response;

import com.psar.notification.entity.NotificationType;

import java.time.Instant;
import java.util.UUID;

/** One inbox entry. Never carries the recipient's user id. */
public record NotificationResponse(
        UUID publicId,
        NotificationType type,
        String title,
        String message,
        String link,
        boolean read,
        Instant createdAt
) {
}
