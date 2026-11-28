package com.psar.promotion.service;

import com.psar.promotion.dto.response.PromotionStatus;
import com.psar.promotion.entity.Promotion;
import com.psar.promotion.entity.PromotionType;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Locale;

/** Filters for the promotion lists. Each returns null when the filter is not set (Spring ignores it). */
final class PromotionSpecifications {

    private static final char ESCAPE = '\\';

    private PromotionSpecifications() {
    }

    /** Same rule as {@link PromotionStatus#of}, in SQL. */
    static Specification<Promotion> hasStatus(PromotionStatus status, Instant now) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> switch (status) {
            case INACTIVE -> cb.isFalse(root.get("active"));
            case SCHEDULED -> cb.and(cb.isTrue(root.get("active")), cb.greaterThan(root.get("startDate"), now));
            case ACTIVE -> cb.and(cb.isTrue(root.get("active")),
                    cb.lessThanOrEqualTo(root.get("startDate"), now), cb.greaterThan(root.get("endDate"), now));
            case ENDED -> cb.and(cb.isTrue(root.get("active")), cb.lessThanOrEqualTo(root.get("endDate"), now));
        };
    }

    static Specification<Promotion> hasType(PromotionType type) {
        return type == null ? null : (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    /** true = coupons only, false = automatic only. */
    static Specification<Promotion> isCoupon(Boolean coupon) {
        if (coupon == null) {
            return null;
        }
        return (root, query, cb) -> coupon ? cb.isNotNull(root.get("couponCode")) : cb.isNull(root.get("couponCode"));
    }

    /** Case-insensitive match on the name or the coupon code. */
    static Specification<Promotion> matches(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String pattern = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, ESCAPE),
                cb.like(cb.lower(root.get("couponCode")), pattern, ESCAPE));
    }

    /** Storefront: active, in its dates, automatic, not members-only. */
    static Specification<Promotion> shownInStore(Instant now) {
        return hasStatus(PromotionStatus.ACTIVE, now)
                .and(isCoupon(false))
                .and((root, query, cb) -> cb.isFalse(root.get("membersOnly")));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
