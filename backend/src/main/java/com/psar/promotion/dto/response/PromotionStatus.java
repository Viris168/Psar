package com.psar.promotion.dto.response;

import java.time.Instant;

/** Computed from {@code is_active} and the dates, never stored. */
public enum PromotionStatus {
    /** Active, starts later. */
    SCHEDULED,
    /** Active and in its dates. */
    ACTIVE,
    /** Active, end date passed. */
    ENDED,
    /** Deactivated by a manager (whatever the dates). */
    INACTIVE;

    public static PromotionStatus of(boolean active, Instant start, Instant end, Instant now) {
        if (!active) {
            return INACTIVE;
        }
        if (now.isBefore(start)) {
            return SCHEDULED;
        }
        return now.isBefore(end) ? ACTIVE : ENDED;
    }
}
