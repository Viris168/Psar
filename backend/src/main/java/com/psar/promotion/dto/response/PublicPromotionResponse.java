package com.psar.promotion.dto.response;

import com.psar.promotion.entity.PromotionScope;
import com.psar.promotion.entity.PromotionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A running automatic promotion for the storefront. Never a coupon code, usage limit or usage count.
 * Targets by slug (the storefront links to /products/{slug} and /categories/{slug}).
 */
public record PublicPromotionResponse(
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
        BigDecimal minOrderAmount,
        List<String> productSlugs,
        List<String> categorySlugs,
        Instant endsAt
) {
}
