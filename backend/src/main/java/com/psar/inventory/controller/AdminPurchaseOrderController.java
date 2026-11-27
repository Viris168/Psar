package com.psar.inventory.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.common.web.PageResponse;
import com.psar.inventory.dto.request.CreatePurchaseOrderRequest;
import com.psar.inventory.dto.response.PurchaseOrderDetailResponse;
import com.psar.inventory.dto.response.PurchaseOrderResponse;
import com.psar.inventory.entity.PurchaseOrderStatus;
import com.psar.inventory.service.PurchaseOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Admin - purchase orders")
@RestController
@RequestMapping("/api/admin/purchase-orders")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@RequiredArgsConstructor
public class AdminPurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @Operation(summary = "List purchase orders, optionally by status and supplier (newest first)")
    @GetMapping
    public PageResponse<PurchaseOrderResponse> list(
            @RequestParam(required = false) PurchaseOrderStatus status,
            @RequestParam(required = false) UUID supplier,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return purchaseOrderService.list(status, supplier, pageable);
    }

    @Operation(summary = "Get one purchase order with its items")
    @GetMapping("/{poNo}")
    public PurchaseOrderDetailResponse get(@PathVariable String poNo) {
        return purchaseOrderService.get(poNo);
    }

    @Operation(summary = "Create a PENDING purchase order (the total is calculated by the server)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOrderDetailResponse create(@AuthenticationPrincipal AuthenticatedUser me,
                                              @Valid @RequestBody CreatePurchaseOrderRequest request) {
        return purchaseOrderService.create(request, me.userId());
    }

    @Operation(summary = "Receive a PENDING purchase order: adds its items to stock")
    @PostMapping("/{poNo}/receive")
    public PurchaseOrderDetailResponse receive(@AuthenticationPrincipal AuthenticatedUser me,
                                               @PathVariable String poNo) {
        return purchaseOrderService.receive(poNo, me.userId());
    }

    @Operation(summary = "Cancel a PENDING purchase order")
    @PostMapping("/{poNo}/cancel")
    public PurchaseOrderDetailResponse cancel(@PathVariable String poNo) {
        return purchaseOrderService.cancel(poNo);
    }
}
