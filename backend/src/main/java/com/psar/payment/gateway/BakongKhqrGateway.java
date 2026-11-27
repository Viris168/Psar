package com.psar.payment.gateway;

import com.psar.payment.entity.PaymentProvider;
import com.psar.payment.service.bakong.BakongClient;
import com.psar.payment.service.confirm.PaymentConfirmation;
import com.psar.payment.service.khqr.KhqrCode;
import com.psar.payment.service.khqr.KhqrRequest;
import com.psar.payment.service.khqr.KhqrService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Bakong KHQR: the QR is made locally by {@link KhqrService}; payment is checked by md5 with {@link BakongClient}. */
@Component
@RequiredArgsConstructor
public class BakongKhqrGateway implements PaymentGateway {

    private final KhqrService khqrService;
    private final BakongClient bakongClient;
    private final KhqrWebhookParser webhookParser;

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.BAKONG_KHQR;
    }

    @Override
    public boolean supports(String paymentMethod) {
        return "KHQR".equals(paymentMethod);
    }

    /** Null fields until KhqrService is implemented (Step 0). */
    @Override
    public GatewayCharge createCharge(ChargeRequest request) {
        KhqrCode code = khqrService.generate(new KhqrRequest(request.reference(), request.amount(),
                request.currency(), request.expiresAt()));
        return code == null ? new GatewayCharge(null, null, null) : new GatewayCharge(code.md5(), code.qr(), null);
    }

    @Override
    public Optional<GatewayPayment> checkStatus(String providerRef) {
        return providerRef == null ? Optional.empty() : bakongClient.findPaid(providerRef);
    }

    @Override
    public Optional<PaymentConfirmation> parseCallback(Map<String, String> headers, byte[] body) {
        return webhookParser.parse(headers, body);
    }
}
