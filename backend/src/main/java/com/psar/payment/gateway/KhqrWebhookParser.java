package com.psar.payment.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psar.payment.config.PaymentProperties;
import com.psar.payment.entity.PaymentEventSource;
import com.psar.payment.entity.PaymentProvider;
import com.psar.payment.service.confirm.PaymentConfirmation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a signed KHQR webhook call ({@code X-Signature} = hex HMAC-SHA256 of the raw body with
 * {@code app.payment.webhook.secret}, compared in constant time). Bakong itself only offers polling ("check
 * transaction by md5"); this is the format for a relay / notifier that knows our shared secret.
 * <p>
 * Body (field names as in Bakong's transaction {@code data}):
 * <pre>
 * { "md5": "&lt;qr md5&gt;", "hash": "&lt;bank transaction ref&gt;", "amount": 12.50, "currency": "USD",
 *   "acknowledgedDateMs": 1767225600000 }        // paid time, optional
 * </pre>
 * <ul>
 *   <li>missing / wrong signature, or no secret configured → a confirmation with {@code signatureValid = false}
 *       and no data from the body (the processor records it as REJECTED; the body is not stored)</li>
 *   <li>valid signature but not JSON → empty</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KhqrWebhookParser {

    public static final String SIGNATURE_HEADER = "X-Signature";
    private static final String HMAC = "HmacSHA256";

    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;

    public Optional<PaymentConfirmation> parse(Map<String, String> headers, byte[] body) {
        if (!signatureValid(header(headers, SIGNATURE_HEADER), body)) {
            return Optional.of(new PaymentConfirmation(PaymentEventSource.WEBHOOK, PaymentProvider.BAKONG_KHQR,
                    null, null, null, null, null, Map.of("rejected", "bad signature", "bodyBytes", body.length),
                    false));
        }
        Map<String, Object> json;
        try {
            json = objectMapper.readValue(body, new TypeReference<>() {
            });
        } catch (IOException ex) {
            log.warn("Signed KHQR webhook body is not a JSON object; ignored");
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(new PaymentConfirmation(PaymentEventSource.WEBHOOK, PaymentProvider.BAKONG_KHQR,
                text(json.get("md5")), text(json.get("hash")), decimal(json.get("amount")), text(json.get("currency")),
                epochMillis(json.get("acknowledgedDateMs")), json, true));
    }

    /** Hex HMAC-SHA256 of the body: what a sender puts in {@code X-Signature}. */
    public static String sign(byte[] body, String secret) {
        return HexFormat.of().formatHex(hmac(body, secret));
    }

    private boolean signatureValid(String signature, byte[] body) {
        String secret = properties.webhook() == null ? null : properties.webhook().secret();
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signature.trim().toLowerCase());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(hmac(body, secret), given);
    }

    private static byte[] hmac(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return mac.doFinal(body);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream().filter(e -> name.equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Instant epochMillis(Object value) {
        return value instanceof Number number ? Instant.ofEpochMilli(number.longValue()) : null;
    }
}
