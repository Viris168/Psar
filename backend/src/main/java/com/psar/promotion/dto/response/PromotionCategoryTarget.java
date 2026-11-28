package com.psar.promotion.dto.response;

/** A category an item promotion applies to (a top-level one covers its subcategories). */
public record PromotionCategoryTarget(
        String slug,
        String name
) {
}
