package com.psar.payment.gateway;

import com.psar.payment.entity.PaymentProvider;
import com.psar.payment.service.confirm.PaymentConfirmation;

import java.util.Map;
import java.util.Optional;

/**
 * One payment provider. Bakong KHQR now; ABA PayWay / cards plug in later as new implementations, without
 * touching checkout, orders or {@code PaymentProcessor}. Only {@code PaymentService}, the check job and the
 * webhook talk to gateways, through {@link PaymentGateways}.
 */
public interface PaymentGateway {

    PaymentProvider provider();

    /** The order module's payment method name, e.g. "KHQR". */
    boolean supports(String paymentMethod);

    /** Must not call the network inside checkout (CLAUDE.md): KHQR is generated locally. */
    GatewayCharge createCharge(ChargeRequest request);

    /** Empty = not paid (yet), or the gateway couldn't be reached. Never throws for "not found". */
    Optional<GatewayPayment> checkStatus(String providerRef);

    /**
     * A webhook call, signature already checked by the implementation. Empty = not a payment we can use;
     * a confirmation with {@code signatureValid = false} must be recorded and rejected.
     */
    Optional<PaymentConfirmation> parseCallback(Map<String, String> headers, byte[] body);
}
