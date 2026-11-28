package com.psar.promotion.service;

import com.psar.promotion.dto.request.PromotionCartLine;
import com.psar.promotion.dto.response.OfferSet;
import com.psar.promotion.dto.response.PromotionOffer;
import com.psar.promotion.entity.DiscountLevel;
import com.psar.promotion.entity.Promotion;
import com.psar.promotion.entity.PromotionTarget;
import com.psar.promotion.repository.OrderPromotionRepository;
import com.psar.promotion.repository.PromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Which promotions can apply to a cart, for the checkout quote and checkout (the order module calls this; it may
 * not read promotion tables itself).
 */
@Service
@RequiredArgsConstructor
public class PromotionService {

    private final PromotionRepository promotionRepository;
    private final OrderPromotionRepository orderPromotionRepository;

    /**
     * Every promotion that can apply to these lines for this user at {@code now}:
     * <ul>
     *   <li>automatic ones that are active, in their dates, with uses left (usage limit and this user's
     *       per-user limit, counting only uses that were not given back) and matching at least one line;
     *       ITEM offers list exactly the matching product ids (CATEGORIES: the category or its parent)</li>
     *   <li>members-only promotions are skipped (Phase 8 has members)</li>
     *   <li>the coupon, looked up case-insensitively, if it passes the same checks; otherwise
     *       {@link OfferSet#couponProblem} says why (unknown, not started, ended, used up, already used by
     *       you, members only, nothing in the cart it applies to)</li>
     * </ul>
     * The minimum order amount is left to pricing (it depends on item discounts). The usage limit read here is
     * only a first filter: {@link PromotionUsageService#claim} is what makes it hold under concurrent checkouts.
     *
     * @param couponCode as the customer typed it; null / blank = none
     */
    @Transactional(readOnly = true)
    public OfferSet offersFor(Long userId, List<PromotionCartLine> lines, String couponCode, Instant now) {
        String code = Promotion.normaliseCode(couponCode);
        List<Promotion> automatic = promotionRepository.findRunningAutomatic(now);
        Optional<Promotion> coupon = code == null ? Optional.empty() : promotionRepository.findByCouponCode(code);

        List<Promotion> considered = new ArrayList<>(automatic);
        coupon.ifPresent(considered::add);
        Map<Long, Long> myUses = myActiveUses(userId, considered);

        List<PromotionOffer> offers = new ArrayList<>();
        for (Promotion promotion : automatic) {
            if (!overPerUserLimit(promotion, myUses)) {
                toOffer(promotion, lines).ifPresent(offers::add);
            }
        }

        String problem = null;
        if (code != null) {
            problem = coupon.isEmpty() ? unknown(code) : couponProblem(coupon.get(), code, now, myUses);
            if (problem == null) {
                Optional<PromotionOffer> offer = toOffer(coupon.get(), lines);
                if (offer.isPresent()) {
                    offers.add(offer.get());
                } else {
                    problem = "Coupon " + code + " doesn't apply to any product in your cart";
                }
            }
        }
        return new OfferSet(offers, code, problem);
    }

    /** promotion id → this user's uses not given back, for the promotions that have a per-user limit. */
    private Map<Long, Long> myActiveUses(Long userId, Collection<Promotion> promotions) {
        Set<Long> limited = promotions.stream()
                .filter(p -> p.getPerUserLimit() != null)
                .map(Promotion::getId)
                .collect(Collectors.toSet());
        if (limited.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> uses = new HashMap<>();
        for (Object[] row : orderPromotionRepository.countActiveUses(userId, limited)) {
            uses.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return uses;
    }

    private static boolean overPerUserLimit(Promotion promotion, Map<Long, Long> myUses) {
        return promotion.getPerUserLimit() != null
                && myUses.getOrDefault(promotion.getId(), 0L) >= promotion.getPerUserLimit();
    }

    /** Why this coupon can't be used now, for the customer; null if it can (its targets are checked after). */
    private static String couponProblem(Promotion coupon, String code, Instant now, Map<Long, Long> myUses) {
        if (!coupon.isActive()) {
            return unknown(code);
        }
        if (now.isBefore(coupon.getStartDate())) {
            return "Coupon " + code + " can't be used yet";
        }
        if (!coupon.isRunningAt(now)) {
            return "Coupon " + code + " has expired";
        }
        if (coupon.isMembersOnly()) {
            return "Coupon " + code + " is for members only";
        }
        if (coupon.getUsageLimit() != null && coupon.getUsedCount() >= coupon.getUsageLimit()) {
            return "Coupon " + code + " has been fully used";
        }
        if (overPerUserLimit(coupon, myUses)) {
            return "You have already used coupon " + code;
        }
        return null;
    }

    private static String unknown(String code) {
        return "Coupon " + code + " is not valid";
    }

    /** The offer pricing needs; empty for an item promotion that matches none of the lines. */
    private static Optional<PromotionOffer> toOffer(Promotion promotion, List<PromotionCartLine> lines) {
        DiscountLevel level = promotion.getApplyTo().isItemLevel() ? DiscountLevel.ITEM : DiscountLevel.ORDER;
        Set<Long> productIds = matchingProducts(promotion, lines);
        if (level == DiscountLevel.ITEM && productIds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PromotionOffer(promotion.getId(), promotion.getName(), promotion.getType().name(),
                level.name(), promotion.getDiscountValue(), promotion.getMaxDiscount(),
                promotion.getBuyQuantity(), promotion.getGetQuantity(), promotion.getMinOrderAmount(),
                productIds, promotion.getCouponCode(), promotion.getEndDate()));
    }

    /** ITEM: the cart's product ids the promotion covers; ORDER: none. */
    private static Set<Long> matchingProducts(Promotion promotion, List<PromotionCartLine> lines) {
        return switch (promotion.getApplyTo()) {
            case ALL -> productIds(lines.stream());
            case PRODUCTS -> {
                Set<Long> targets = targetIds(promotion, PromotionTarget::getProductId);
                yield productIds(lines.stream().filter(line -> targets.contains(line.productId())));
            }
            case CATEGORIES -> {
                Set<Long> targets = targetIds(promotion, PromotionTarget::getCategoryId);
                yield productIds(lines.stream().filter(line -> targets.contains(line.categoryId())
                        || (line.parentCategoryId() != null && targets.contains(line.parentCategoryId()))));
            }
            case ORDER -> Set.of();
        };
    }

    private static Set<Long> productIds(java.util.stream.Stream<PromotionCartLine> lines) {
        return lines.map(PromotionCartLine::productId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<Long> targetIds(Promotion promotion, Function<PromotionTarget, Long> id) {
        return promotion.getTargets().stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
    }
}
