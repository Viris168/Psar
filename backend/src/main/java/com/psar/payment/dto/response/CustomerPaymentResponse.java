package com.psar.payment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * My order's payment. {@code qrString} is the KHQR to scan, present only while the payment is PENDING and not
 * expired. {@code publicId} identifies the payment (e.g. for "simulate payment" in the demo). Never the md5, the
 * bank's transaction reference or a raw payload.
 */
public record CustomerPaymentResponse(
        UUID publicId,
        String status,
        String method,
        BigDecimal amount,
        String currency,
        Instant expiresAt,
        String qrString,
        Instant paidAt
) {

    public static CustomerPaymentResponse of(PaymentView payment, Instant now) {
        boolean scannable = "PENDING".equals(payment.status())
                && (payment.expiresAt() == null || now.isBefore(payment.expiresAt()));
        return new CustomerPaymentResponse(payment.publicId(), payment.status(), payment.method(), payment.amount(), payment.currency(),
                payment.expiresAt(), scannable ? payment.qrString() : null, payment.paidAt());
    }
}
