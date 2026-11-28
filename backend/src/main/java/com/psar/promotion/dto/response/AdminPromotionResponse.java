package com.psar.promotion.dto.response;

import com.psar.promotion.entity.PromotionScope;
import com.psar.promotion.entity.PromotionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One promotion for managers: everything, including the coupon code, limits and usage. */
public record AdminPromotionResponse(
        UUID publicId,
        String name,
        String description,
        String bannerUrl,
        PromotionType type,
        BigDecimal discountValue,
        BigDecimal maxDiscount,
        Integer buyQuantity,
        Integer getQuantity,
        PromotionScope applyTo,
        List<PromotionProductTarget> products,
        List<PromotionCategoryTarget> categories,
        BigDecimal minOrderAmount,
        String couponCode,
        boolean membersOnly,
        Integer usageLimit,
        Integer perUserLimit,
        int usedCount,
        Instant startDate,
        Instant endDate,
        boolean active,
        PromotionStatus status,
        boolean broadcastOnStart,
        Instant broadcastAt,
        Instant createdAt,
        Instant updatedAt
) {
}
