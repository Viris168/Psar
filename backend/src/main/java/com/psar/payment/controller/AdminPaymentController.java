package com.psar.payment.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.common.web.PageResponse;
import com.psar.payment.dto.request.MarkRefundedRequest;
import com.psar.payment.dto.response.AdminPaymentDetailResponse;
import com.psar.payment.dto.response.AdminPaymentListItemResponse;
import com.psar.payment.entity.PaymentMethod;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.service.admin.AdminPaymentService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@Tag(name = "Admin - payments")
@RestController
@RequestMapping("/api/admin/payments")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@RequiredArgsConstructor
public class AdminPaymentController {

    private final AdminPaymentService adminPaymentService;

    @Operation(summary = "List payments (sort: createdAt, paidAt, amount, status; default newest first)")
    @GetMapping
    public PageResponse<AdminPaymentListItemResponse> list(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) PaymentMethod method,
            @Parameter(description = "true: paid after the order was cancelled (refund by hand)")
            @RequestParam(required = false) Boolean refundRequired,
            @Parameter(description = "Created at, inclusive, ISO-8601 instant")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Created at, exclusive, ISO-8601 instant")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return adminPaymentService.list(new AdminPaymentService.Filter(status, method, refundRequired, from, to),
                pageable);
    }

    @Operation(summary = "Payment detail with every confirmation attempt (payloads included)")
    @GetMapping("/{publicId}")
    public AdminPaymentDetailResponse get(@PathVariable UUID publicId) {
        return adminPaymentService.get(publicId);
    }

    @Operation(summary = "Mark a late (refund-required) payment as refunded by hand; 409 if not refund-required "
            + "or already refunded")
    @PostMapping("/{publicId}/refunded")
    public AdminPaymentDetailResponse markRefunded(@AuthenticationPrincipal AuthenticatedUser me,
                                                   @PathVariable UUID publicId,
                                                   @Valid @RequestBody MarkRefundedRequest request) {
        return adminPaymentService.markRefundedByHand(publicId, request.reason(), me.userId());
    }
}
