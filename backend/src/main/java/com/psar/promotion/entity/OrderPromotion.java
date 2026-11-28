package com.psar.promotion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A promotion an order used, with its name and code as they were at checkout. Only {@code releasedAt} ever
 * changes: set once when the order is cancelled, expires or is refunded and the use is given back.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "order_promotions")
public class OrderPromotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "promotion_id", nullable = false, updatable = false)
    private Long promotionId;

    @Column(name = "promotion_name", nullable = false, length = 150, updatable = false)
    private String promotionName;

    @Column(name = "coupon_code", length = 30, updatable = false)
    private String couponCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 10, updatable = false)
    private DiscountLevel level;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal discountAmount;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Setter
    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static OrderPromotion of(Long orderId, Long userId, Promotion promotion, DiscountLevel level,
                                    BigDecimal discountAmount, Instant now) {
        OrderPromotion row = new OrderPromotion();
        row.orderId = orderId;
        row.userId = userId;
        row.promotionId = promotion.getId();
        row.promotionName = promotion.getName();
        row.couponCode = promotion.getCouponCode();
        row.level = level;
        row.discountAmount = discountAmount;
        row.createdAt = now;
        return row;
    }

    public boolean isReleased() {
        return releasedAt != null;
    }
}
