package com.psar.payment.service.khqr;

import com.psar.payment.config.PaymentProperties;
import kh.gov.nbc.bakong_khqr.BakongKHQR;
import kh.gov.nbc.bakong_khqr.model.IndividualInfo;
import kh.gov.nbc.bakong_khqr.model.KHQRCurrency;
import kh.gov.nbc.bakong_khqr.model.KHQRData;
import kh.gov.nbc.bakong_khqr.model.KHQRResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Builds KHQR codes with the NBC SDK ({@code kh.gov.nbc.bakong_khqr.BakongKHQR}), locally (no network), for the
 * shop's Bakong account in {@code app.payment.khqr.*}: an individual KHQR in USD with the order total, the order
 * number as bill number and the order's expiry.
 * <p>
 * Any SDK failure throws {@link KhqrGenerationException} (logged with the order number), which rolls the whole
 * checkout back: never an order without its code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KhqrService {

    private static final String USD = "USD";

    private final PaymentProperties properties;

    public KhqrCode generate(KhqrRequest request) {
        String billNumber = request.billNumber();
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw fail(billNumber, "amount must be positive");
        }
        if (request.expiresAt() == null) {
            throw fail(billNumber, "expiry is required");
        }
        String currency = request.currency() == null ? USD : request.currency();
        if (!USD.equalsIgnoreCase(currency)) {
            throw fail(billNumber, "unsupported currency " + currency);
        }

        PaymentProperties.Khqr khqr = properties.khqr();
        IndividualInfo info = new IndividualInfo();
        info.setBakongAccountId(khqr.accountId());
        info.setMerchantName(khqr.merchantName());
        info.setMerchantCity(khqr.merchantCity());
        info.setCurrency(KHQRCurrency.USD);
        info.setAmount(toSdkAmount(request.amount()));
        info.setBillNumber(billNumber);
        // a KHQR with an amount needs an expiry (SDK error 45 otherwise): epoch milliseconds
        info.setExpirationTimestamp(request.expiresAt().toEpochMilli());

        KHQRResponse<KHQRData> response;
        try {
            response = BakongKHQR.generateIndividual(info);
        } catch (RuntimeException e) {
            log.error("KHQR generation failed for order {}", billNumber, e);
            throw new KhqrGenerationException("Could not create the KHQR code for order " + billNumber, e);
        }
        if (response == null || response.getKHQRStatus() == null || response.getKHQRStatus().getCode() != 0
                || response.getData() == null || response.getData().getQr() == null
                || response.getData().getMd5() == null) {
            String status = response == null || response.getKHQRStatus() == null ? "no status"
                    : "errorCode=%s %s".formatted(response.getKHQRStatus().getErrorCode(),
                    response.getKHQRStatus().getMessage());
            throw fail(billNumber, status);
        }
        return new KhqrCode(response.getData().getQr(), response.getData().getMd5());
    }

    /**
     * The SDK takes a {@code Double}: round to cents first (HALF_UP), so 10.005 becomes 10.01, and convert only
     * here. A 2-decimal BigDecimal converts to the nearest double, which the SDK prints back as the same cents.
     */
    static double toSdkAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static KhqrGenerationException fail(String billNumber, String reason) {
        log.error("KHQR generation failed for order {}: {}", billNumber, reason);
        return new KhqrGenerationException("Could not create the KHQR code for order " + billNumber + ": " + reason);
    }
}
