package com.psar.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.payment.*}. Secrets (Bakong token, webhook secret) come from environment variables only and are
 * never logged or returned.
 *
 * @param khqr     the shop's Bakong account shown in every KHQR code
 * @param bakong   how payments are confirmed: {@code fake} (dev / tests, default) or {@code bakong}
 * @param webhook  shared secret for signed webhook calls
 * @param simulate dev / demo only: "simulate payment"
 * @param check    the payment-check job
 */
@ConfigurationProperties(prefix = "app.payment")
public record PaymentProperties(Khqr khqr, Bakong bakong, Webhook webhook, Simulate simulate, Check check) {

    public record Khqr(String accountId, String merchantName, String merchantCity, String currency) {
    }

    public record Bakong(String mode, String baseUrl, String token, Duration timeout) {
        public boolean fake() {
            return !"bakong".equalsIgnoreCase(mode);
        }
    }

    public record Webhook(String secret) {
    }

    public record Simulate(boolean enabled) {
    }

    /**
     * @param minAge           only check payments at least this old (the customer needs time to scan)
     * @param interval         don't check the same payment more often than this
     * @param lateWindow       keep checking expired payments this long, to catch late payments
     */
    public record Check(Duration minAge, Duration interval, Duration lateWindow) {
    }
}
