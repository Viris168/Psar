package com.psar.payment.service.bakong;

import com.psar.payment.gateway.GatewayPayment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bakong for dev and tests (the default): nothing is paid until {@link #markPaid} says so, e.g. from the
 * "simulate payment" endpoint or a test. Never used when {@code app.payment.bakong.mode=bakong}.
 */
@Component
@ConditionalOnProperty(prefix = "app.payment.bakong", name = "mode", havingValue = "fake", matchIfMissing = true)
public class FakeBakongClient implements BakongClient {

    private final Map<String, GatewayPayment> paid = new ConcurrentHashMap<>();

    /** Pretend the customer paid this amount for the QR with this md5. Returns the fake bank reference. */
    public String markPaid(String qrMd5, BigDecimal amount, String currency) {
        String transactionRef = "FAKE-" + UUID.randomUUID();
        paid.put(qrMd5, new GatewayPayment(transactionRef, amount, currency, Instant.now(),
                Map.of("fake", true, "md5", qrMd5, "hash", transactionRef)));
        return transactionRef;
    }

    public void forget(String qrMd5) {
        paid.remove(qrMd5);
    }

    @Override
    public Optional<GatewayPayment> findPaid(String qrMd5) {
        return Optional.ofNullable(paid.get(qrMd5));
    }
}
