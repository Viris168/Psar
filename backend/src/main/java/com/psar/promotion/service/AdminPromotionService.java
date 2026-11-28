package com.psar.promotion.service;

import com.psar.audit.service.AuditEntity;
import com.psar.audit.service.AuditFields;
import com.psar.audit.service.AuditService;
import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.NotFoundException;
import com.psar.common.web.PageResponse;
import com.psar.promotion.dto.request.PromotionRequest;
import com.psar.promotion.dto.response.AdminPromotionResponse;
import com.psar.promotion.dto.response.AdminPromotionSummaryResponse;
import com.psar.promotion.dto.response.PromotionStatus;
import com.psar.promotion.entity.Promotion;
import com.psar.promotion.entity.PromotionScope;
import com.psar.promotion.entity.PromotionTarget;
import com.psar.promotion.entity.PromotionType;
import com.psar.promotion.event.PromotionBroadcastEvent;
import com.psar.promotion.mapper.PromotionMapper;
import com.psar.promotion.repository.OrderPromotionRepository;
import com.psar.promotion.repository.PromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Managers' promotions: create, edit, list, deactivate, broadcast. The usage count is never written here (only
 * PromotionUsageService's atomic update changes it).
 * <p>
 * Once a promotion has been used by an order (even a cancelled one), its <b>terms</b> (type, value, max discount,
 * buy / get, scope, targets, coupon code) are frozen, so old orders keep their meaning: deactivate it and create
 * a new one. Name, texts, dates, limits and flags can still change.
 */
@Service
@RequiredArgsConstructor
public class AdminPromotionService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORTABLE = Set.of("name", "startDate", "endDate", "createdAt", "usedCount");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Pattern COUPON_CODE = Pattern.compile("^[A-Z0-9_-]{3,30}$");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PromotionRepository promotionRepository;
    private final OrderPromotionRepository orderPromotionRepository;
    private final PromotionMapper promotionMapper;
    private final PromotionTargetResolver targetResolver;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ---- reading ---------------------------------------------------------------------------------

    /**
     * All promotions, newest first by default.
     *
     * @param coupon true = coupons only, false = automatic only, null = both
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminPromotionSummaryResponse> list(PromotionStatus status, PromotionType type, String q,
                                                            Boolean coupon, Pageable pageable) {
        Instant now = clock.instant();
        Specification<Promotion> spec = Specification.allOf(
                PromotionSpecifications.hasStatus(status, now),
                PromotionSpecifications.hasType(type),
                PromotionSpecifications.isCoupon(coupon),
                PromotionSpecifications.matches(q));
        return PageResponse.from(promotionRepository.findAll(spec, safePage(pageable))
                .map(p -> promotionMapper.toSummary(p, statusOf(p, now))));
    }

    @Transactional(readOnly = true)
    public AdminPromotionResponse get(UUID publicId) {
        return toResponse(find(publicId));
    }

    // ---- writing ---------------------------------------------------------------------------------

    @Transactional
    public AdminPromotionResponse create(PromotionRequest request, Long createdBy) {
        Terms terms = validate(request, null);
        Promotion promotion = new Promotion();
        promotionMapper.updateDetails(request, promotion);
        applyTerms(promotion, terms);
        promotion.setCreatedBy(createdBy);
        promotion = promotionRepository.save(promotion);
        auditService.recordCreate(AuditEntity.PROMOTION, promotion.getPublicId().toString(),
                auditFields(promotion).toMap());
        return toResponse(promotion);
    }

    /** PUT: every field from the request (null {@code active} / flags = unchanged). */
    @Transactional
    public AdminPromotionResponse update(UUID publicId, PromotionRequest request) {
        Promotion promotion = find(publicId);
        Terms terms = validate(request, promotion);
        if (!terms.sameAs(promotion) && hasBeenUsed(promotion)) {
            throw new ConflictException("This promotion has been used: its type, value, targets and coupon code "
                    + "can't change. Deactivate it and create a new one.");
        }
        AuditFields before = auditFields(promotion);
        promotionMapper.updateDetails(request, promotion);
        applyTerms(promotion, terms);
        promotionRepository.save(promotion);
        auditService.recordChanges(AuditEntity.PROMOTION, publicId.toString(), before, auditFields(promotion));
        return toResponse(promotion);
    }

    /** Soft: {@code is_active = false} (orders keep pointing at it). Already inactive → no change. */
    @Transactional
    public void deactivate(UUID publicId) {
        Promotion promotion = find(publicId);
        if (!promotion.isActive()) {
            return;
        }
        AuditFields before = auditFields(promotion);
        promotion.setActive(false);
        promotionRepository.save(promotion);
        auditService.recordChanges(AuditEntity.PROMOTION, publicId.toString(), before, auditFields(promotion));
    }

    /**
     * Announces a running promotion to every active customer, once: a second broadcast is a 409. The
     * notifications are written after this commits (PromotionBroadcastEvent).
     */
    @Transactional
    public AdminPromotionResponse broadcast(UUID publicId) {
        Promotion promotion = find(publicId);
        Instant now = clock.instant();
        PromotionStatus status = statusOf(promotion, now);
        if (promotion.getBroadcastAt() != null) {
            throw alreadyBroadcast();
        }
        if (status == PromotionStatus.SCHEDULED) {
            throw new BusinessRuleException("This promotion hasn't started yet: set broadcastOnStart to announce "
                    + "it when it starts");
        }
        if (status != PromotionStatus.ACTIVE) {
            throw new BusinessRuleException("Only a running promotion can be broadcast (this one is " + status + ")");
        }
        if (promotionRepository.markBroadcast(promotion.getId(), now) == 0) {
            throw alreadyBroadcast();
        }
        recordBroadcast(promotion, now);
        return toResponse(promotion);
    }

    /**
     * PromotionLaunchJob: broadcasts one promotion that started with {@code broadcast_on_start}, if it still
     * qualifies and nobody broadcast it in the meantime. Returns whether it was broadcast.
     */
    @Transactional
    public boolean launch(Long promotionId) {
        Instant now = clock.instant();
        Promotion promotion = promotionRepository.findById(promotionId).orElse(null);
        if (promotion == null || !promotion.isBroadcastOnStart() || promotion.getBroadcastAt() != null
                || statusOf(promotion, now) != PromotionStatus.ACTIVE
                || promotionRepository.markBroadcast(promotionId, now) == 0) {
            return false;
        }
        recordBroadcast(promotion, now);
        return true;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void recordBroadcast(Promotion promotion, Instant now) {
        AuditFields before = AuditFields.create().put("broadcastAt", null);
        promotion.setBroadcastAt(now); // same value markBroadcast wrote; keeps the response current
        auditService.recordChanges(AuditEntity.PROMOTION, promotion.getPublicId().toString(), before,
                AuditFields.create().put("broadcastAt", now.toString()));
        eventPublisher.publishEvent(new PromotionBroadcastEvent(promotion.getId()));
    }

    private static ConflictException alreadyBroadcast() {
        return new ConflictException("This promotion was already broadcast");
    }

    /** Used by any order, even one cancelled since (its order_promotions row still describes it). */
    private boolean hasBeenUsed(Promotion promotion) {
        return promotion.getUsedCount() > 0 || orderPromotionRepository.existsByPromotionId(promotion.getId());
    }

    /**
     * Checks the request against the rules for its type (422 with one message per field), then the coupon
     * code's uniqueness (409). {@code existing} is null on create.
     */
    private Terms validate(PromotionRequest r, Promotion existing) {
        Map<String, String> errors = new LinkedHashMap<>();
        PromotionType type = r.type();
        PromotionScope scope = r.applyTo();
        BigDecimal value = r.discountValue();

        switch (type) {
            case PERCENTAGE -> {
                if (value == null) {
                    errors.put("discountValue", "is required for PERCENTAGE");
                } else if (value.compareTo(BigDecimal.ONE) < 0 || value.compareTo(HUNDRED) > 0) {
                    errors.put("discountValue", "must be between 1 and 100 (%)");
                }
            }
            case FIXED_AMOUNT -> {
                if (value == null) {
                    errors.put("discountValue", "is required for FIXED_AMOUNT");
                } else if (value.signum() <= 0) {
                    errors.put("discountValue", "must be greater than 0");
                }
            }
            case BUY_X_GET_Y -> {
                if (value != null) {
                    errors.put("discountValue", "must be empty for BUY_X_GET_Y");
                }
                if (r.buyQuantity() == null || r.buyQuantity() < 1) {
                    errors.put("buyQuantity", "must be at least 1");
                }
                if (r.getQuantity() == null || r.getQuantity() < 1) {
                    errors.put("getQuantity", "must be at least 1");
                }
                if (scope == PromotionScope.ORDER) {
                    errors.put("applyTo", "BUY_X_GET_Y applies to PRODUCTS, CATEGORIES or ALL, not ORDER");
                }
            }
            case FREE_DELIVERY -> {
                if (value != null) {
                    errors.put("discountValue", "must be empty for FREE_DELIVERY");
                }
                if (scope != PromotionScope.ORDER) {
                    errors.put("applyTo", "FREE_DELIVERY applies to the ORDER");
                }
            }
        }
        if (type != PromotionType.BUY_X_GET_Y) {
            if (r.buyQuantity() != null) {
                errors.put("buyQuantity", "only for BUY_X_GET_Y");
            }
            if (r.getQuantity() != null) {
                errors.put("getQuantity", "only for BUY_X_GET_Y");
            }
        }
        if (r.maxDiscount() != null) {
            if (type != PromotionType.PERCENTAGE) {
                errors.put("maxDiscount", "only for PERCENTAGE");
            } else if (r.maxDiscount().signum() <= 0) {
                errors.put("maxDiscount", "must be greater than 0");
            }
        }

        Set<Long> productIds = resolveProducts(r.productIds(), scope, errors);
        Set<Long> categoryIds = resolveCategories(r.categorySlugs(), scope, errors);

        String code = Promotion.normaliseCode(r.couponCode());
        if (code != null && !COUPON_CODE.matcher(code).matches()) {
            errors.put("couponCode", "must be 3–30 characters: letters, digits, _ or -");
        }
        if (!r.endDate().isAfter(r.startDate())) {
            errors.put("endDate", "must be after startDate");
        }
        if (r.usageLimit() != null && r.perUserLimit() != null && r.perUserLimit() > r.usageLimit()) {
            errors.put("perUserLimit", "can't be more than usageLimit");
        }
        if (existing != null && r.usageLimit() != null && r.usageLimit() < existing.getUsedCount()) {
            errors.put("usageLimit", "can't be lower than the " + existing.getUsedCount() + " uses already made");
        }
        if (!errors.isEmpty()) {
            throw new BusinessRuleException("Promotion is not valid", errors);
        }

        if (code != null && (existing == null
                ? promotionRepository.existsByCouponCode(code)
                : promotionRepository.existsByCouponCodeAndIdNot(code, existing.getId()))) {
            throw new ConflictException("Coupon code " + code + " is already used by another promotion");
        }
        return new Terms(type, money(value), money(r.maxDiscount()), r.buyQuantity(), r.getQuantity(), scope,
                productIds, categoryIds, code);
    }

    private Set<Long> resolveProducts(List<UUID> requested, PromotionScope scope, Map<String, String> errors) {
        List<UUID> publicIds = requested == null ? List.of() : requested;
        if (scope != PromotionScope.PRODUCTS) {
            if (!publicIds.isEmpty()) {
                errors.put("productIds", "only for apply_to PRODUCTS");
            }
            return Set.of();
        }
        if (publicIds.isEmpty()) {
            errors.put("productIds", "at least one product is required for apply_to PRODUCTS");
            return Set.of();
        }
        Map<UUID, Long> found = targetResolver.productIds(publicIds);
        String missing = publicIds.stream().filter(id -> !found.containsKey(id)).distinct()
                .map(UUID::toString).collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            errors.put("productIds", "unknown products: " + missing);
        }
        return new LinkedHashSet<>(found.values());
    }

    private Set<Long> resolveCategories(List<String> requested, PromotionScope scope, Map<String, String> errors) {
        List<String> slugs = requested == null ? List.of() : requested;
        if (scope != PromotionScope.CATEGORIES) {
            if (!slugs.isEmpty()) {
                errors.put("categorySlugs", "only for apply_to CATEGORIES");
            }
            return Set.of();
        }
        if (slugs.isEmpty()) {
            errors.put("categorySlugs", "at least one category is required for apply_to CATEGORIES");
            return Set.of();
        }
        Map<String, Long> found = targetResolver.categoryIds(slugs);
        String missing = slugs.stream().filter(slug -> !found.containsKey(slug)).distinct()
                .collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            errors.put("categorySlugs", "unknown categories: " + missing);
        }
        return new LinkedHashSet<>(found.values());
    }

    /** Sets the terms; targets are diffed (not cleared and re-added, which would break their UNIQUE keys). */
    private static void applyTerms(Promotion promotion, Terms terms) {
        promotion.setType(terms.type());
        promotion.setDiscountValue(terms.discountValue());
        promotion.setMaxDiscount(terms.maxDiscount());
        promotion.setBuyQuantity(terms.buyQuantity());
        promotion.setGetQuantity(terms.getQuantity());
        promotion.setApplyTo(terms.applyTo());
        promotion.setCouponCode(terms.couponCode());

        List<PromotionTarget> targets = promotion.getTargets();
        targets.removeIf(t -> t.getProductId() != null
                ? !terms.productIds().contains(t.getProductId())
                : !terms.categoryIds().contains(t.getCategoryId()));
        Set<Long> haveProducts = targets.stream().map(PromotionTarget::getProductId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> haveCategories = targets.stream().map(PromotionTarget::getCategoryId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        terms.productIds().stream().filter(id -> !haveProducts.contains(id)).forEach(promotion::addProductTarget);
        terms.categoryIds().stream().filter(id -> !haveCategories.contains(id)).forEach(promotion::addCategoryTarget);
    }

    private AdminPromotionResponse toResponse(Promotion promotion) {
        PromotionTargetResolver.Described targets = targetResolver.describeTargets(promotion.getTargets());
        return promotionMapper.toAdminResponse(promotion, statusOf(promotion, clock.instant()),
                targets.products(), targets.categories());
    }

    private static PromotionStatus statusOf(Promotion p, Instant now) {
        return PromotionStatus.of(p.isActive(), p.getStartDate(), p.getEndDate(), now);
    }

    private static AuditFields auditFields(Promotion p) {
        return AuditFields.create().put("name", p.getName()).put("type", p.getType())
                .put("discountValue", p.getDiscountValue()).put("maxDiscount", p.getMaxDiscount())
                .put("buyQuantity", p.getBuyQuantity()).put("getQuantity", p.getGetQuantity())
                .put("applyTo", p.getApplyTo())
                .put("productTargets", sortedIds(p, true)).put("categoryTargets", sortedIds(p, false))
                .put("minOrderAmount", p.getMinOrderAmount()).put("couponCode", p.getCouponCode())
                .put("membersOnly", p.isMembersOnly()).put("usageLimit", p.getUsageLimit())
                .put("perUserLimit", p.getPerUserLimit())
                .put("startDate", p.getStartDate() == null ? null : p.getStartDate().toString())
                .put("endDate", p.getEndDate() == null ? null : p.getEndDate().toString())
                .put("active", p.isActive()).put("broadcastOnStart", p.isBroadcastOnStart());
    }

    private static List<Long> sortedIds(Promotion p, boolean products) {
        return p.getTargets().stream()
                .map(products ? PromotionTarget::getProductId : PromotionTarget::getCategoryId)
                .filter(Objects::nonNull).sorted().toList();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private Promotion find(UUID publicId) {
        return promotionRepository.findByPublicId(publicId).orElseThrow(() -> NotFoundException.of("Promotion"));
    }

    /** Caps the page size and only allows sorting on known properties (unknown ones would be a 500). */
    private static Pageable safePage(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .filter(order -> SORTABLE.contains(order.getProperty()))
                .toList());
        return PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                sort.isSorted() ? sort.and(Sort.by(Sort.Order.desc("id"))) : DEFAULT_SORT);
    }

    /** What may not change once the promotion was used. */
    private record Terms(PromotionType type, BigDecimal discountValue, BigDecimal maxDiscount, Integer buyQuantity,
                         Integer getQuantity, PromotionScope applyTo, Set<Long> productIds, Set<Long> categoryIds,
                         String couponCode) {

        boolean sameAs(Promotion p) {
            return type == p.getType() && sameMoney(discountValue, p.getDiscountValue())
                    && sameMoney(maxDiscount, p.getMaxDiscount())
                    && Objects.equals(buyQuantity, p.getBuyQuantity())
                    && Objects.equals(getQuantity, p.getGetQuantity())
                    && applyTo == p.getApplyTo() && Objects.equals(couponCode, p.getCouponCode())
                    && productIds.equals(Set.copyOf(sortedIds(p, true)))
                    && categoryIds.equals(Set.copyOf(sortedIds(p, false)));
        }

        private static boolean sameMoney(BigDecimal a, BigDecimal b) {
            return a == null ? b == null : b != null && a.compareTo(b) == 0;
        }
    }
}
