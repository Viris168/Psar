package com.psar.payment.gateway;

import com.psar.payment.service.confirm.PaymentConfirmation;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Reads a signed KHQR webhook call ({@code X-Signature} = hex HMAC-SHA256 of the raw body with
 * {@code app.payment.webhook.secret}, compared in constant time).
 * <p>
 * Step 0 version: accepts nothing. Agent B (Phase 5) implements it; the signature stays.
 */
@Component
public class KhqrWebhookParser {

    public Optional<PaymentConfirmation> parse(Map<String, String> headers, byte[] body) {
        return Optional.empty();
    }
}
