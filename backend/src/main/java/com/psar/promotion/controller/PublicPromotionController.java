package com.psar.promotion.controller;

import com.psar.common.web.PageResponse;
import com.psar.promotion.dto.response.PublicPromotionResponse;
import com.psar.promotion.service.PublicPromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Storefront, no login (/api/public/** is open in SecurityConfig). */
@Tag(name = "Promotions")
@RestController
@RequestMapping("/api/public/promotions")
@RequiredArgsConstructor
public class PublicPromotionController {

    private final PublicPromotionService publicPromotionService;

    @Operation(summary = "Running automatic promotions (no coupons, limits or usage); ending soonest first")
    @GetMapping
    public PageResponse<PublicPromotionResponse> list(@ParameterObject Pageable pageable) {
        return publicPromotionService.listRunning(pageable);
    }
}
