package com.psar.promotion.mapper;

import com.psar.common.mapper.CentralMapperConfig;
import com.psar.promotion.dto.request.PromotionRequest;
import com.psar.promotion.dto.response.AdminPromotionResponse;
import com.psar.promotion.dto.response.AdminPromotionSummaryResponse;
import com.psar.promotion.dto.response.PromotionAnnouncement;
import com.psar.promotion.dto.response.PromotionCategoryTarget;
import com.psar.promotion.dto.response.PromotionProductTarget;
import com.psar.promotion.dto.response.PromotionStatus;
import com.psar.promotion.dto.response.PublicPromotionResponse;
import com.psar.promotion.entity.Promotion;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * Promotion ↔ DTOs. The terms of a promotion (type, value, buy / get, scope, targets, coupon code) are set by
 * AdminPromotionService itself, because changing them is guarded once the promotion was used; this mapper only
 * copies the other fields. {@code usedCount}, {@code broadcastAt} and {@code createdBy} are never taken from a
 * request.
 */
@Mapper(config = CentralMapperConfig.class)
public interface PromotionMapper {

    @Mapping(target = "status", source = "status")
    @Mapping(target = "products", source = "products")
    @Mapping(target = "categories", source = "categories")
    AdminPromotionResponse toAdminResponse(Promotion promotion, PromotionStatus status,
                                           List<PromotionProductTarget> products,
                                           List<PromotionCategoryTarget> categories);

    @Mapping(target = "status", source = "status")
    AdminPromotionSummaryResponse toSummary(Promotion promotion, PromotionStatus status);

    @Mapping(target = "productSlugs", source = "productSlugs")
    @Mapping(target = "categorySlugs", source = "categorySlugs")
    @Mapping(target = "endsAt", source = "promotion.endDate")
    PublicPromotionResponse toPublic(Promotion promotion, List<String> productSlugs, List<String> categorySlugs);

    @Mapping(target = "endsAt", source = "endDate")
    PromotionAnnouncement toAnnouncement(Promotion promotion);

    /** The fields anyone may change at any time. Null {@code active} / flags = unchanged. */
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "discountValue", ignore = true)
    @Mapping(target = "maxDiscount", ignore = true)
    @Mapping(target = "buyQuantity", ignore = true)
    @Mapping(target = "getQuantity", ignore = true)
    @Mapping(target = "applyTo", ignore = true)
    @Mapping(target = "couponCode", ignore = true)
    @Mapping(target = "targets", ignore = true)
    @Mapping(target = "broadcastAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    void updateDetails(PromotionRequest request, @MappingTarget Promotion promotion);
}
