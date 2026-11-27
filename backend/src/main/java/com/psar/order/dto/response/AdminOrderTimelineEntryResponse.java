package com.psar.order.dto.response;

import com.psar.user.dto.response.UserSummary;

import java.time.Instant;

/** One status change for staff: {@code changedBy} is null when the system (expiry job) made it. */
public record AdminOrderTimelineEntryResponse(String status, String note, UserSummary changedBy, Instant createdAt) {
}
