package com.psar.payment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** One confirmation attempt, payload included: admins / managers only, never customers. */
public record PaymentEventResponse(
        String source,
        String provider,
        String providerRef,
        String transactionRef,
        BigDecimal amount,
        String currency,
        Map<String, Object> payload,
        Boolean signatureValid,
        String outcome,
        Instant receivedAt
) {
}
