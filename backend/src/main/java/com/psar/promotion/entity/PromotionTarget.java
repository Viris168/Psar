package com.psar.promotion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One product or one category (exactly one) an item promotion applies to. A top-level category also covers its
 * subcategories. Ids only: the catalog's entities belong to the catalog module.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "promotion_targets")
public class PromotionTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_id", nullable = false, updatable = false)
    private Promotion promotion;

    @Column(name = "product_id", updatable = false)
    private Long productId;

    @Column(name = "category_id", updatable = false)
    private Long categoryId;

    static PromotionTarget product(Promotion promotion, Long productId) {
        PromotionTarget target = new PromotionTarget();
        target.promotion = promotion;
        target.productId = productId;
        return target;
    }

    static PromotionTarget category(Promotion promotion, Long categoryId) {
        PromotionTarget target = new PromotionTarget();
        target.promotion = promotion;
        target.categoryId = categoryId;
        return target;
    }
}
