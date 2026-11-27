package com.psar.payment.controller;

import com.psar.payment.dto.response.WebhookAckResponse;
import com.psar.payment.service.webhook.PaymentWebhookService;
import com.psar.payment.service.webhook.WebhookPayloadTooLargeException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Payment notifications from a gateway. No JWT (open in SecurityConfig): every call must be signed; the
 * gateway checks the signature. 200 for every outcome (also duplicates), 401 for a bad signature.
 */
@Tag(name = "Payments")
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentWebhookService webhookService;

    @Operation(summary = "Payment notification (signed: X-Signature = hex HMAC-SHA256 of the raw body)",
            description = "provider: bakong-khqr. Body ≤ 64 KB. 200 for every outcome, 401 for a bad signature.")
    @SecurityRequirements
    @PostMapping("/{provider}")
    public WebhookAckResponse receive(@Parameter(example = "bakong-khqr") @PathVariable String provider,
                                      HttpServletRequest request) throws IOException {
        byte[] body = readBody(request);
        return new WebhookAckResponse(webhookService.receive(provider, headers(request), body).name());
    }

    /** The raw bytes (the signature covers them exactly), refusing anything over the limit before reading it all. */
    private static byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > PaymentWebhookService.MAX_BODY_BYTES) {
            throw new WebhookPayloadTooLargeException();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(PaymentWebhookService.MAX_BODY_BYTES + 1);
            if (body.length > PaymentWebhookService.MAX_BODY_BYTES) {
                throw new WebhookPayloadTooLargeException();
            }
            return body;
        }
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
