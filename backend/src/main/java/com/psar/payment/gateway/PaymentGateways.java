package com.psar.payment.gateway;

import com.psar.common.exception.NotFoundException;
import com.psar.payment.entity.PaymentProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Picks the gateway for a payment method or provider. */
@Component
public class PaymentGateways {

    private final List<PaymentGateway> gateways;

    public PaymentGateways(List<PaymentGateway> gateways) {
        this.gateways = List.copyOf(gateways);
    }

    /** Empty for methods without a gateway (cash on delivery). */
    public Optional<PaymentGateway> forMethod(String paymentMethod) {
        return gateways.stream().filter(gateway -> gateway.supports(paymentMethod)).findFirst();
    }

    public PaymentGateway forProvider(PaymentProvider provider) {
        return gateways.stream().filter(gateway -> gateway.provider() == provider).findFirst()
                .orElseThrow(() -> new IllegalStateException("No gateway for " + provider));
    }

    /** Webhook path segment, e.g. "bakong-khqr" → BAKONG_KHQR; unknown → 404. */
    public PaymentGateway forPath(String providerPath) {
        String name = providerPath == null ? "" : providerPath.trim().toUpperCase().replace('-', '_');
        return gateways.stream().filter(gateway -> gateway.provider().name().equals(name)).findFirst()
                .orElseThrow(() -> NotFoundException.of("Payment provider"));
    }
}
