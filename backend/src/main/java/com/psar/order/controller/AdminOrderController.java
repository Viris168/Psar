package com.psar.order.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.common.web.PageResponse;
import com.psar.order.dto.request.AdminCancelOrderRequest;
import com.psar.order.dto.request.AssignDriverRequest;
import com.psar.order.dto.request.ChangeOrderStatusRequest;
import com.psar.order.dto.request.TrackingNumberRequest;
import com.psar.order.dto.response.AdminOrderDetailResponse;
import com.psar.order.dto.response.AdminOrderListItemResponse;
import com.psar.order.entity.DeliveryType;
import com.psar.order.entity.OrderStatus;
import com.psar.order.service.orders.AdminOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@Tag(name = "Admin - orders")
@RestController
@RequestMapping("/api/admin/orders")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
@RequiredArgsConstructor
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    @Operation(summary = "List orders (sort: createdAt, orderNo, totalAmount, status; default newest first)")
    @GetMapping
    public PageResponse<AdminOrderListItemResponse> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) DeliveryType deliveryType,
            @Parameter(description = "Created at, inclusive, ISO-8601 instant")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Created at, exclusive, ISO-8601 instant")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Order number or receiver phone (or part of it)")
            @RequestParam(required = false) String q,
            @Parameter(description = "Driver's user publicId")
            @RequestParam(required = false) UUID assignedTo,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return adminOrderService.list(new AdminOrderService.Filter(status, deliveryType, from, to, q, assignedTo),
                pageable);
    }

    @Operation(summary = "Order detail with customer, driver and full status history")
    @GetMapping("/{publicId}")
    public AdminOrderDetailResponse get(@PathVariable UUID publicId) {
        return adminOrderService.get(publicId);
    }

    @Operation(summary = "Move the order one step on (409 INVALID_STATUS if the flow doesn't allow it)")
    @PatchMapping("/{publicId}/status")
    public AdminOrderDetailResponse changeStatus(@AuthenticationPrincipal AuthenticatedUser me,
                                                 @PathVariable UUID publicId,
                                                 @Valid @RequestBody ChangeOrderStatusRequest request) {
        return adminOrderService.changeStatus(publicId, request.status(), request.note(), me.userId());
    }

    @Operation(summary = "Assign the driver of a CITY order")
    @PutMapping("/{publicId}/driver")
    public AdminOrderDetailResponse assignDriver(@PathVariable UUID publicId,
                                                 @Valid @RequestBody AssignDriverRequest request) {
        return adminOrderService.assignDriver(publicId, request.driverPublicId());
    }

    @Operation(summary = "Set the courier tracking number of a PROVINCE order")
    @PutMapping("/{publicId}/tracking")
    public AdminOrderDetailResponse setTracking(@PathVariable UUID publicId,
                                                @Valid @RequestBody TrackingNumberRequest request) {
        return adminOrderService.setTrackingNo(publicId, request.trackingNo());
    }

    @Operation(summary = "Cancel an order that hasn't left the shop (ADMIN, MANAGER): stock back, payment cancelled")
    @PostMapping("/{publicId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public AdminOrderDetailResponse cancel(@AuthenticationPrincipal AuthenticatedUser me,
                                           @PathVariable UUID publicId,
                                           @Valid @RequestBody AdminCancelOrderRequest request) {
        return adminOrderService.cancel(publicId, request.reason(), me.userId());
    }
}
