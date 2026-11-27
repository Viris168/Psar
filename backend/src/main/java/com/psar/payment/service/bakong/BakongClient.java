package com.psar.payment.service.bakong;

import com.psar.payment.gateway.GatewayPayment;

import java.util.Optional;

/**
 * Asks Bakong whether the KHQR with this md5 was paid. Empty = not paid yet, unknown, or Bakong unreachable:
 * implementations never throw for those, so the check job just tries again later.
 * {@code app.payment.bakong.mode}: {@code fake} → {@link FakeBakongClient}; {@code bakong} → the HTTP client
 * (Agent B).
 */
public interface BakongClient {

    Optional<GatewayPayment> findPaid(String qrMd5);
}
