package com.psar.promotion.service;

import com.psar.catalog.dto.response.CategoryReference;
import com.psar.catalog.dto.response.ProductReference;
import com.psar.catalog.service.CatalogReferenceService;
import com.psar.promotion.dto.response.PromotionCategoryTarget;
import com.psar.promotion.dto.response.PromotionProductTarget;
import com.psar.promotion.entity.PromotionTarget;
import com.psar.promotion.repository.PromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Translates promotion targets between what the API shows (product public ids / slugs, category slugs) and what
 * promotion_targets stores (internal ids), through the catalog's service. Batches, so a page has no N+1.
 */
@Component
@RequiredArgsConstructor
class PromotionTargetResolver {

    private final CatalogReferenceService catalogReferenceService;
    private final PromotionRepository promotionRepository;

    /** Targets of one promotion as shown to managers / customers. */
    record Described(List<PromotionProductTarget> products, List<PromotionCategoryTarget> categories) {

        static final Described NONE = new Described(List.of(), List.of());

        List<String> productSlugs() {
            return products.stream().map(PromotionProductTarget::slug).toList();
        }

        List<String> categorySlugs() {
            return categories.stream().map(PromotionCategoryTarget::slug).toList();
        }
    }

    /** Internal ids by public id; an unknown public id is absent. */
    Map<UUID, Long> productIds(Collection<UUID> publicIds) {
        return catalogReferenceService.productsByPublicIds(new LinkedHashSet<>(publicIds)).stream()
                .collect(Collectors.toMap(ProductReference::publicId, ProductReference::id));
    }

    /** Internal ids by slug; an unknown slug is absent. */
    Map<String, Long> categoryIds(Collection<String> slugs) {
        return catalogReferenceService.categoriesBySlugs(new LinkedHashSet<>(slugs)).stream()
                .collect(Collectors.toMap(CategoryReference::slug, CategoryReference::id));
    }

    Described describeTargets(List<PromotionTarget> targets) {
        return describeAll(targets).values().stream().findFirst().orElse(Described.NONE);
    }

    /** Targets of many promotions (one page): one query for the targets, one per catalog table. */
    Map<Long, Described> describe(Collection<Long> promotionIds) {
        if (promotionIds.isEmpty()) {
            return Map.of();
        }
        return describeAll(promotionRepository.findTargetsByPromotionIdIn(promotionIds));
    }

    private Map<Long, Described> describeAll(List<PromotionTarget> targets) {
        Set<Long> productIds = targets.stream().map(PromotionTarget::getProductId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> categoryIds = targets.stream().map(PromotionTarget::getCategoryId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductReference> products = catalogReferenceService.productsByIds(productIds).stream()
                .collect(Collectors.toMap(ProductReference::id, Function.identity()));
        Map<Long, CategoryReference> categories = catalogReferenceService.categoriesByIds(categoryIds).stream()
                .collect(Collectors.toMap(CategoryReference::id, Function.identity()));

        Map<Long, List<PromotionTarget>> byPromotion = targets.stream()
                .collect(Collectors.groupingBy(t -> t.getPromotion().getId(), LinkedHashMap::new,
                        Collectors.toList()));
        Map<Long, Described> described = new LinkedHashMap<>();
        byPromotion.forEach((promotionId, rows) -> described.put(promotionId, new Described(
                rows.stream().map(PromotionTarget::getProductId).filter(Objects::nonNull)
                        .map(products::get).filter(Objects::nonNull)
                        .map(p -> new PromotionProductTarget(p.publicId(), p.slug(), p.name()))
                        .sorted(Comparator.comparing(PromotionProductTarget::name))
                        .toList(),
                rows.stream().map(PromotionTarget::getCategoryId).filter(Objects::nonNull)
                        .map(categories::get).filter(Objects::nonNull)
                        .map(c -> new PromotionCategoryTarget(c.slug(), c.name()))
                        .sorted(Comparator.comparing(PromotionCategoryTarget::name))
                        .toList())));
        return described;
    }
}
