package com.psar.payment.service.khqr;

import com.psar.payment.config.PaymentProperties;
import kh.gov.nbc.bakong_khqr.BakongKHQR;
import kh.gov.nbc.bakong_khqr.model.KHQRDecodeData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** KhqrService with the real SDK, offline. */
class KhqrServiceTest {

    private static final Instant EXPIRES_AT = Instant.now().plus(15, ChronoUnit.MINUTES);

    private final KhqrService service = new KhqrService(properties("psar_shop@devb"));

    @Test
    void generate_validOrder_qrPassesSdkVerifyAndDecodesToAmountAndBillNumber() {
        KhqrCode code = service.generate(new KhqrRequest("ORD-000042", new BigDecimal("12.50"), "USD", EXPIRES_AT));

        assertThat(code.md5()).hasSize(32);
        assertThat(BakongKHQR.verify(code.qr()).getData().isValid()).isTrue();
        KHQRDecodeData decoded = BakongKHQR.decode(code.qr()).getData();
        assertThat(new BigDecimal(decoded.getTransactionAmount())).isEqualByComparingTo("12.50");
        assertThat(decoded.getBillNumber()).isEqualTo("ORD-000042");
        assertThat(decoded.getBakongAccountID()).isEqualTo("psar_shop@devb");
        assertThat(decoded.getMerchantName()).isEqualTo("Psar Shop");
        assertThat(decoded.getMerchantCity()).isEqualTo("Phnom Penh");
        assertThat(decoded.getTransactionCurrency()).isEqualTo("840"); // ISO 4217 USD
        assertThat(Long.parseLong(decoded.getExpirationTimestamp())).isEqualTo(EXPIRES_AT.toEpochMilli());
    }

    @Test
    void generate_amountRounding_twoDecimals() {
        assertThat(KhqrService.toSdkAmount(new BigDecimal("10.005"))).isEqualTo(10.01);
        assertThat(KhqrService.toSdkAmount(new BigDecimal("0.10"))).isEqualTo(0.1);

        for (String amount : new String[]{"10.005", "0.10", "19.99", "1234.56", "0.01"}) {
            KhqrCode code = service.generate(new KhqrRequest("ORD-1", new BigDecimal(amount), "USD", EXPIRES_AT));
            String sent = BakongKHQR.decode(code.qr()).getData().getTransactionAmount();
            BigDecimal expected = new BigDecimal(amount).setScale(2, java.math.RoundingMode.HALF_UP);
            assertThat(sent).as(amount).doesNotContain("99999").doesNotContain("00000");
            assertThat(new BigDecimal(sent)).as(amount).isEqualByComparingTo(expected);
        }
    }

    @Test
    void generate_sameOrderDifferentAmount_differentMd5() {
        KhqrCode a = service.generate(new KhqrRequest("ORD-7", new BigDecimal("5.00"), "USD", EXPIRES_AT));
        KhqrCode b = service.generate(new KhqrRequest("ORD-7", new BigDecimal("5.01"), "USD", EXPIRES_AT));

        assertThat(a.md5()).isNotEqualTo(b.md5());
    }

    @Test
    void generate_sdkRejectsAccount_throwsWithOrderNumber() {
        KhqrService broken = new KhqrService(properties(""));

        assertThatThrownBy(() -> broken.generate(new KhqrRequest("ORD-9", BigDecimal.ONE, "USD", EXPIRES_AT)))
                .isInstanceOf(KhqrGenerationException.class)
                .hasMessageContaining("ORD-9");
    }

    @Test
    void generate_khrOrMissingExpiry_throws() {
        assertThatThrownBy(() -> service.generate(new KhqrRequest("ORD-9", BigDecimal.ONE, "KHR", EXPIRES_AT)))
                .isInstanceOf(KhqrGenerationException.class);
        assertThatThrownBy(() -> service.generate(new KhqrRequest("ORD-9", BigDecimal.ONE, "USD", null)))
                .isInstanceOf(KhqrGenerationException.class);
    }

    private static PaymentProperties properties(String accountId) {
        return new PaymentProperties(new PaymentProperties.Khqr(accountId, "Psar Shop", "Phnom Penh", "USD"),
                null, null, null, null);
    }
}
