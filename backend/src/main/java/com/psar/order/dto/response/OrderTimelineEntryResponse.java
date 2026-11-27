package com.psar.order.dto.response;

import java.time.Instant;

/** One step of the customer's timeline. Who changed it is never shown to customers. */
public record OrderTimelineEntryResponse(String status, String note, Instant createdAt) {
}
