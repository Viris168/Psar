package com.psar.promotion.service;

import com.psar.common.web.PageResponse;
import com.psar.promotion.dto.response.PromotionAnnouncement;
import com.psar.promotion.dto.response.PublicPromotionResponse;
import com.psar.promotion.entity.Promotion;
import com.psar.promotion.mapper.PromotionMapper;
import com.psar.promotion.repository.PromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What customers may see of promotions: the running automatic ones (storefront), and the text of a broadcast.
 * Coupon codes, limits and usage never leave through the storefront list.
 */
@Service
@RequiredArgsConstructor
public class PublicPromotionService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORTABLE = Set.of("name", "startDate", "endDate");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("endDate"), Sort.Order.asc("id"));

    private final PromotionRepository promotionRepository;
    private final PromotionMapper promotionMapper;
    private final PromotionTargetResolver targetResolver;
    private final Clock clock;

    /**
     * Active, in their dates, automatic (no coupon) and not members-only (nobody is a member before Phase 8).
     * Ending soonest first by default.
     */
    @Transactional(readOnly = true)
    public PageResponse<PublicPromotionResponse> listRunning(Pageable pageable) {
        Page<Promotion> page = promotionRepository.findAll(
                PromotionSpecifications.shownInStore(clock.instant()), safePage(pageable));
        Map<Long, PromotionTargetResolver.Described> targets = targetResolver.describe(
                page.getContent().stream().map(Promotion::getId).toList());
        return PageResponse.from(page.map(p -> {
            PromotionTargetResolver.Described described =
                    targets.getOrDefault(p.getId(), PromotionTargetResolver.Described.NONE);
            return promotionMapper.toPublic(p, described.productSlugs(), described.categorySlugs());
        }));
    }

    /**
     * The broadcast notification's content (it does include the coupon code: announcing it is the point).
     * Empty if the promotion is gone.
     */
    @Transactional(readOnly = true)
    public Optional<PromotionAnnouncement> announcement(Long promotionId) {
        return promotionRepository.findById(promotionId).map(promotionMapper::toAnnouncement);
    }

    private static Pageable safePage(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .filter(order -> SORTABLE.contains(order.getProperty()))
                .toList());
        return PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                sort.isSorted() ? sort.and(Sort.by(Sort.Order.asc("id"))) : DEFAULT_SORT);
    }
}
