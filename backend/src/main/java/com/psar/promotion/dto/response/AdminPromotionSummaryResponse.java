package com.psar.promotion.dto.response;

import com.psar.promotion.entity.PromotionScope;
import com.psar.promotion.entity.PromotionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A row of the admin promotion list (targets: see the detail). */
public record AdminPromotionSummaryResponse(
        UUID publicId,
        String name,
        PromotionType type,
        BigDecimal discountValue,
        Integer buyQuantity,
        Integer getQuantity,
        PromotionScope applyTo,
        String couponCode,
        boolean membersOnly,
        Integer usageLimit,
        int usedCount,
        Instant startDate,
        Instant endDate,
        boolean active,
        PromotionStatus status,
        Instant broadcastAt
) {
}
