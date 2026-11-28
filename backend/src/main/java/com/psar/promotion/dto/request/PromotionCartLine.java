package com.psar.promotion.dto.request;

/**
 * A cart line as promotion eligibility sees it: which product, and its category and that category's parent
 * (null for a top-level category), so a CATEGORIES target on a top-level category also covers its subcategories.
 * Internal ids: never in an API.
 */
public record PromotionCartLine(Long productId, Long categoryId, Long parentCategoryId) {
}
