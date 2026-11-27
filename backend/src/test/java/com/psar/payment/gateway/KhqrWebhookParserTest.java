package com.psar.payment.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.psar.payment.config.PaymentProperties;
import com.psar.payment.entity.PaymentEventSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KhqrWebhookParserTest {

    private static final String SECRET = "unit-secret";
    private static final byte[] BODY = """
            {"md5":"abc","hash":"TX-1","amount":12.50,"currency":"USD","acknowledgedDateMs":1767225600000}"""
            .getBytes(StandardCharsets.UTF_8);

    private final KhqrWebhookParser parser = parser(SECRET);

    @Test
    void parse_validSignature_confirmationWithBodyFields() {
        assertThat(parser.parse(Map.of("x-signature", KhqrWebhookParser.sign(BODY, SECRET)), BODY)).get()
                .satisfies(c -> {
                    assertThat(c.signatureValid()).isTrue();
                    assertThat(c.source()).isEqualTo(PaymentEventSource.WEBHOOK);
                    assertThat(c.providerRef()).isEqualTo("abc");
                    assertThat(c.transactionRef()).isEqualTo("TX-1");
                    assertThat(c.amount()).isEqualByComparingTo("12.50");
                    assertThat(c.currency()).isEqualTo("USD");
                    assertThat(c.paidAt()).isEqualTo(Instant.ofEpochMilli(1767225600000L));
                });
    }

    @Test
    void parse_tamperedBodyOrWrongSecret_signatureInvalidNoBodyData() {
        byte[] tampered = new String(BODY, StandardCharsets.UTF_8).replace("12.50", "0.01")
                .getBytes(StandardCharsets.UTF_8);
        String signature = KhqrWebhookParser.sign(BODY, SECRET);

        assertThat(parser.parse(Map.of("X-Signature", signature), tampered)).get().satisfies(c -> {
            assertThat(c.signatureValid()).isFalse();
            assertThat(c.providerRef()).isNull();
            assertThat(c.amount()).isNull();
        });
        assertThat(parser.parse(Map.of("X-Signature", KhqrWebhookParser.sign(BODY, "other")), BODY)).get()
                .satisfies(c -> assertThat(c.signatureValid()).isFalse());
        assertThat(parser.parse(Map.of("X-Signature", "not-hex"), BODY)).get()
                .satisfies(c -> assertThat(c.signatureValid()).isFalse());
        assertThat(parser.parse(Map.of(), BODY)).get().satisfies(c -> assertThat(c.signatureValid()).isFalse());
    }

    @Test
    void parse_noSecretConfigured_alwaysInvalid() {
        assertThat(parser("").parse(Map.of("X-Signature", KhqrWebhookParser.sign(BODY, "any")), BODY)).get()
                .satisfies(c -> assertThat(c.signatureValid()).isFalse());
    }

    @Test
    void parse_signedButNotJson_empty() {
        byte[] body = "not json".getBytes(StandardCharsets.UTF_8);

        assertThat(parser.parse(Map.of("X-Signature", KhqrWebhookParser.sign(body, SECRET)), body)).isEmpty();
    }

    private static KhqrWebhookParser parser(String secret) {
        return new KhqrWebhookParser(new PaymentProperties(null, null, new PaymentProperties.Webhook(secret), null,
                null), new ObjectMapper());
    }
}
