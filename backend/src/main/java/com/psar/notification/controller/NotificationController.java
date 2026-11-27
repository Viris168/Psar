package com.psar.notification.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.common.web.PageResponse;
import com.psar.notification.dto.response.NotificationResponse;
import com.psar.notification.dto.response.ReadAllResponse;
import com.psar.notification.dto.response.UnreadCountResponse;
import com.psar.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The logged-in user's in-app inbox (any role). Someone else's notification is a 404. */
@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "List my notifications, newest first (sort parameter is ignored)")
    @GetMapping
    public PageResponse<NotificationResponse> list(
            @AuthenticationPrincipal AuthenticatedUser me,
            @Parameter(description = "true = only unread") @RequestParam(defaultValue = "false") boolean unread,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return notificationService.listMine(me.userId(), unread, pageable);
    }

    @Operation(summary = "How many of my notifications are unread")
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal AuthenticatedUser me) {
        return notificationService.unreadCount(me.userId());
    }

    @Operation(summary = "Mark one of my notifications read")
    @PatchMapping("/{publicId}/read")
    public NotificationResponse markRead(@AuthenticationPrincipal AuthenticatedUser me,
                                         @PathVariable UUID publicId) {
        return notificationService.markRead(me.userId(), publicId);
    }

    @Operation(summary = "Mark all my notifications read; returns how many changed")
    @PostMapping("/read-all")
    public ReadAllResponse readAll(@AuthenticationPrincipal AuthenticatedUser me) {
        return notificationService.markAllRead(me.userId());
    }
}
