package com.psar.order.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.common.web.PageResponse;
import com.psar.order.dto.request.CancelOrderRequest;
import com.psar.order.dto.response.OrderDetailResponse;
import com.psar.order.dto.response.OrderListItemResponse;
import com.psar.order.entity.OrderStatus;
import com.psar.order.service.orders.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The logged-in customer's orders. Someone else's order is a 404. */
@Tag(name = "Orders")
@RestController
@RequestMapping("/api/orders")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "List my orders, newest first (sort parameter is ignored)")
    @GetMapping
    public PageResponse<OrderListItemResponse> list(@AuthenticationPrincipal AuthenticatedUser me,
                                                    @RequestParam(required = false) OrderStatus status,
                                                    @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return orderService.listMine(me.userId(), status, pageable);
    }

    @Operation(summary = "My order: items, money, delivery, tracking link and timeline")
    @GetMapping("/{publicId}")
    public OrderDetailResponse get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID publicId) {
        return orderService.getMine(me.userId(), publicId);
    }

    @Operation(summary = "Cancel my order while it is PENDING_PAYMENT or CONFIRMED (reason optional)")
    @PostMapping("/{publicId}/cancel")
    public OrderDetailResponse cancel(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID publicId,
                                      @Valid @RequestBody(required = false) CancelOrderRequest request) {
        return orderService.cancelMine(me.userId(), publicId, request == null ? null : request.reason());
    }
}
