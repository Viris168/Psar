package com.psar.promotion.entity;

/**
 * PERCENTAGE / FIXED_AMOUNT: {@code discount_value} % or $ off. BUY_X_GET_Y: every (buy + get) units of one
 * product, {@code get} are free. FREE_DELIVERY: the delivery fee becomes 0 (order level only).
 */
public enum PromotionType {
    PERCENTAGE,
    FIXED_AMOUNT,
    BUY_X_GET_Y,
    FREE_DELIVERY
}
