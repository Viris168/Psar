package com.psar.promotion.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** What the broadcast notification says about a promotion (loaded after the broadcast committed). */
public record PromotionAnnouncement(
        UUID publicId,
        String name,
        String description,
        String type,
        BigDecimal discountValue,
        Integer buyQuantity,
        Integer getQuantity,
        BigDecimal minOrderAmount,
        String couponCode,
        Instant endsAt,
        boolean active
) {
}
