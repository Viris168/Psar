package com.psar.payment.service.webhook;

import com.psar.common.exception.BadRequestException;
import com.psar.common.exception.UnauthorizedException;
import com.psar.payment.entity.PaymentOutcome;
import com.psar.payment.gateway.PaymentGateway;
import com.psar.payment.gateway.PaymentGateways;
import com.psar.payment.service.confirm.PaymentConfirmation;
import com.psar.payment.service.confirm.PaymentProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * {@code POST /api/payments/webhook/{provider}}: the gateway checks the signature and reads the body, then
 * {@link PaymentProcessor#confirm} decides. Not transactional itself: a rejected call's event row is committed by
 * {@code confirm} before the 401 is thrown.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    /** Larger bodies are refused with 413 before they are read in full. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private final PaymentGateways gateways;
    private final PaymentProcessor processor;

    /**
     * @return the outcome (the caller answers 200 for every one)
     * @throws UnauthorizedException bad or missing signature (recorded as REJECTED first)
     * @throws BadRequestException   signed, but not a payment we can read
     */
    public PaymentOutcome receive(String providerPath, Map<String, String> headers, byte[] body) {
        PaymentGateway gateway = gateways.forPath(providerPath);
        PaymentConfirmation confirmation = gateway.parseCallback(headers, body)
                .orElseThrow(() -> new BadRequestException("Unreadable payment notification"));
        PaymentOutcome outcome = processor.confirm(confirmation);
        if (Boolean.FALSE.equals(confirmation.signatureValid())) {
            log.warn("{} webhook with a bad signature rejected", gateway.provider());
            throw new UnauthorizedException("Invalid webhook signature");
        }
        return outcome;
    }
}
