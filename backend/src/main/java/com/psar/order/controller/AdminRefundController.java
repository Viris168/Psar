package com.psar.order.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.order.dto.request.RefundOrderRequest;
import com.psar.order.dto.response.AdminOrderDetailResponse;
import com.psar.order.service.refund.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Admin - orders")
@RestController
@RequestMapping("/api/admin/orders")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@RequiredArgsConstructor
public class AdminRefundController {

    private final RefundService refundService;

    @Operation(summary = "Refund an order (ADMIN, MANAGER). PAID / PROCESSING KHQR → CANCELLED, stock back; "
            + "DELIVERED → REFUNDED, stock back only if restock. Payment REFUNDED (money returned by hand). "
            + "Anything else or a second refund → 409 INVALID_STATUS")
    @PostMapping("/{publicId}/refund")
    public AdminOrderDetailResponse refund(@AuthenticationPrincipal AuthenticatedUser me,
                                           @PathVariable UUID publicId,
                                           @Valid @RequestBody RefundOrderRequest request) {
        return refundService.refund(publicId, request.reason(), request.restock(), me.userId());
    }
}
