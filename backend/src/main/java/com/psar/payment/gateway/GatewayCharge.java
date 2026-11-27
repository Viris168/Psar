package com.psar.payment.gateway;

/**
 * A charge the gateway created. KHQR fills {@code qrString}; hosted checkouts (PayWay, later) fill
 * {@code redirectUrl}. {@code providerRef} is how the gateway finds it again (Bakong: the QR's md5).
 */
public record GatewayCharge(String providerRef, String qrString, String redirectUrl) {
}
