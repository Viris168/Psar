package com.psar.payment.dto.response;

import com.psar.user.dto.response.UserSummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A payment for admins / managers, with every confirmation attempt (oldest first). */
public record AdminPaymentDetailResponse(
        UUID publicId,
        UUID orderPublicId,
        String orderNo,
        String orderStatus,
        String method,
        String status,
        String provider,
        String providerRef,
        String transactionRef,
        BigDecimal amount,
        String currency,
        Instant expiresAt,
        Instant paidAt,
        Instant lastCheckedAt,
        int checkAttempts,
        boolean refundRequired,
        Instant refundedAt,
        String refundReason,
        UserSummary refundedBy,
        Instant createdAt,
        List<PaymentEventResponse> events
) {
}
