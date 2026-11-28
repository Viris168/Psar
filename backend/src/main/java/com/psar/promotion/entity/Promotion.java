package com.psar.promotion.entity;

import com.psar.common.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A promotion rule. Automatic when {@code couponCode} is null, otherwise the customer enters the code.
 * {@code usedCount} is read-only here: only the atomic UPDATE in PromotionUsageService changes it, so a JPA save
 * of a stale entity can never overwrite it (reload the entity to see the current value).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "promotions")
public class Promotion extends BaseEntity {

    @Setter(AccessLevel.NONE)
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "banner_url", length = 500)
    private String bannerUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private PromotionType type;

    /** % (1–100) for PERCENTAGE, $ for FIXED_AMOUNT; null for BUY_X_GET_Y and FREE_DELIVERY. */
    @Column(name = "discount_value", precision = 12, scale = 2)
    private BigDecimal discountValue;

    /** PERCENTAGE only: never more than this off (per line for item promotions, per order for ORDER). */
    @Column(name = "max_discount", precision = 12, scale = 2)
    private BigDecimal maxDiscount;

    @Column(name = "buy_quantity")
    private Integer buyQuantity;

    @Column(name = "get_quantity")
    private Integer getQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "apply_to", nullable = false, length = 20)
    private PromotionScope applyTo;

    /** Checked against the subtotal after item discounts. */
    @Column(name = "min_order_amount", precision = 12, scale = 2)
    private BigDecimal minOrderAmount;

    /** Upper-case; null = automatic. Use {@link #setCouponCode}, which normalises. */
    @Setter(AccessLevel.NONE)
    @Column(name = "coupon_code", unique = true, length = 30)
    private String couponCode;

    /** Saved now, applies from Phase 8 (subscriptions); until then nobody is a member. */
    @Column(name = "members_only", nullable = false)
    private boolean membersOnly;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "per_user_limit")
    private Integer perUserLimit;

    @Setter(AccessLevel.NONE)
    @Column(name = "used_count", nullable = false, insertable = false, updatable = false)
    private int usedCount;

    @Column(name = "start_date", nullable = false)
    private Instant startDate;

    @Column(name = "end_date", nullable = false)
    private Instant endDate;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "broadcast_on_start", nullable = false)
    private boolean broadcastOnStart;

    @Column(name = "broadcast_at")
    private Instant broadcastAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @OneToMany(mappedBy = "promotion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PromotionTarget> targets = new ArrayList<>();

    /** Trims and upper-cases; blank → null (automatic). */
    public void setCouponCode(String couponCode) {
        this.couponCode = normaliseCode(couponCode);
    }

    public boolean isCoupon() {
        return couponCode != null;
    }

    /** In its dates at {@code now} (start inclusive, end exclusive). Ignores {@code active}. */
    public boolean isRunningAt(Instant now) {
        return !now.isBefore(startDate) && now.isBefore(endDate);
    }

    public void addProductTarget(Long productId) {
        targets.add(PromotionTarget.product(this, productId));
    }

    public void addCategoryTarget(Long categoryId) {
        targets.add(PromotionTarget.category(this, categoryId));
    }

    /** How customer input and stored codes are compared: trimmed, upper-case; blank → null. */
    public static String normaliseCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
