package com.psar.payment.service.confirm;

import com.psar.payment.entity.PaymentEventSource;
import com.psar.payment.entity.PaymentProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * "The gateway says this charge was paid" — from the check job, a webhook or a simulation.
 *
 * @param providerRef    the charge (Bakong KHQR: the QR's md5)
 * @param transactionRef the bank's reference for the money movement
 * @param signatureValid webhooks only (null for the check job and simulations)
 */
public record PaymentConfirmation(
        PaymentEventSource source,
        PaymentProvider provider,
        String providerRef,
        String transactionRef,
        BigDecimal amount,
        String currency,
        Instant paidAt,
        Map<String, Object> payload,
        Boolean signatureValid
) {
}
