package com.psar.promotion.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.common.web.PageResponse;
import com.psar.promotion.dto.request.PromotionRequest;
import com.psar.promotion.dto.response.AdminPromotionResponse;
import com.psar.promotion.dto.response.AdminPromotionSummaryResponse;
import com.psar.promotion.dto.response.PromotionStatus;
import com.psar.promotion.entity.PromotionType;
import com.psar.promotion.service.AdminPromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Admin - promotions")
@RestController
@RequestMapping("/api/admin/promotions")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
@RequiredArgsConstructor
public class AdminPromotionController {

    private final AdminPromotionService adminPromotionService;

    @Operation(summary = "List promotions (automatic and coupons), with usage; newest first by default")
    @GetMapping
    public PageResponse<AdminPromotionSummaryResponse> list(
            @RequestParam(required = false) PromotionStatus status,
            @RequestParam(required = false) PromotionType type,
            @Parameter(description = "Part of the name or coupon code") @RequestParam(required = false) String q,
            @Parameter(description = "true = coupons only, false = automatic only")
            @RequestParam(required = false) Boolean coupon,
            @ParameterObject Pageable pageable) {
        return adminPromotionService.list(status, type, q, coupon, pageable);
    }

    @Operation(summary = "Get one promotion, with its targets and usage")
    @GetMapping("/{publicId}")
    public AdminPromotionResponse get(@PathVariable UUID publicId) {
        return adminPromotionService.get(publicId);
    }

    @Operation(summary = "Create a promotion (automatic, or with a coupon code)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminPromotionResponse create(@Valid @RequestBody PromotionRequest request,
                                         @AuthenticationPrincipal AuthenticatedUser me) {
        return adminPromotionService.create(request, me.userId());
    }

    @Operation(summary = "Replace a promotion's fields (type, value, targets and coupon are frozen once used: 409)")
    @PutMapping("/{publicId}")
    public AdminPromotionResponse update(@PathVariable UUID publicId, @Valid @RequestBody PromotionRequest request) {
        return adminPromotionService.update(publicId, request);
    }

    @Operation(summary = "Deactivate a promotion (soft; set active = true with PUT to restore it)")
    @DeleteMapping("/{publicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID publicId) {
        adminPromotionService.deactivate(publicId);
    }

    @Operation(summary = "Announce a running promotion to every active customer (once; again = 409)")
    @PostMapping("/{publicId}/broadcast")
    public AdminPromotionResponse broadcast(@PathVariable UUID publicId) {
        return adminPromotionService.broadcast(publicId);
    }
}
