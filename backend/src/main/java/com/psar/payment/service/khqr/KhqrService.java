package com.psar.payment.service.khqr;

import com.psar.payment.config.PaymentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds KHQR codes with the NBC SDK ({@code kh.gov.nbc.bakong_khqr.BakongKHQR}), locally (no network), for the
 * shop's Bakong account in {@code app.payment.khqr.*}.
 * <p>
 * Step 0 version: returns {@code null} (no QR yet). Agent A (Phase 5) implements it; the signature stays.
 */
@Service
@RequiredArgsConstructor
public class KhqrService {

    private final PaymentProperties properties;

    public KhqrCode generate(KhqrRequest request) {
        return null;
    }
}
