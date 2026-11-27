package com.psar.inventory.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.common.web.PageResponse;
import com.psar.inventory.dto.request.StockAdjustmentRequest;
import com.psar.inventory.dto.response.LowStockResponse;
import com.psar.inventory.dto.response.StockMovementResponse;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.entity.MovementType;
import com.psar.inventory.service.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@Tag(name = "Admin - inventory")
@RestController
@RequestMapping("/api/admin/inventory")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@RequiredArgsConstructor
public class AdminInventoryController {

    private final StockService stockService;

    @Operation(summary = "Adjust stock by hand (DAMAGE, RETURN or CORRECTION); change is signed")
    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    public StockMovementResponse adjust(@AuthenticationPrincipal AuthenticatedUser me,
                                        @Valid @RequestBody StockAdjustmentRequest request) {
        return stockService.adjust(request.productPublicId(), request.change(), request.reason(), request.note(),
                me.userId());
    }

    @Operation(summary = "Stock movement history, newest first")
    @GetMapping("/movements")
    public PageResponse<StockMovementResponse> movements(
            @Parameter(description = "Product publicId") @RequestParam(required = false) UUID product,
            @RequestParam(required = false) MovementType type,
            @RequestParam(required = false) MovementReason reason,
            @Parameter(description = "Inclusive, ISO-8601 instant, e.g. 2026-01-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Exclusive, ISO-8601 instant")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return stockService.movements(product, type, reason, from, to, pageable);
    }

    @Operation(summary = "Active products at or below their minimum stock (sort: quantity, name, sku)")
    @GetMapping("/low-stock")
    public PageResponse<LowStockResponse> lowStock(@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return stockService.lowStock(pageable);
    }
}
