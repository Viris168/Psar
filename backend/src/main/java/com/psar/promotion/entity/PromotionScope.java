package com.psar.promotion.entity;

/**
 * {@code promotions.apply_to}: ALL / PRODUCTS / CATEGORIES are item promotions (per order line; PRODUCTS and
 * CATEGORIES need {@link PromotionTarget} rows), ORDER is an order-level promotion (one per order).
 */
public enum PromotionScope {
    ALL,
    PRODUCTS,
    CATEGORIES,
    ORDER;

    public boolean isItemLevel() {
        return this != ORDER;
    }
}
