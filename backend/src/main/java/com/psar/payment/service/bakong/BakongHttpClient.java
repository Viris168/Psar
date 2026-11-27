package com.psar.payment.service.bakong;

import com.psar.payment.config.PaymentProperties;
import com.psar.payment.gateway.GatewayPayment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The real Bakong Open API ({@code app.payment.bakong.mode=bakong}): "Check transaction by md5".
 * <p>
 * Contract, from the NBC "Bakong Open API Documentation" v1.0.2, §5 "Check Transaction Status by MD5"
 * (<a href="https://api-bakong.nbc.gov.kh/document">api-bakong.nbc.gov.kh/document</a>, PDF:
 * <a href="https://bakong.nbc.gov.kh/download/KHQR/integration/Bakong%20Open%20API%20Document.pdf">
 * Bakong Open API Document.pdf</a>):
 * <pre>
 * POST {baseUrl}/v1/check_transaction_by_md5
 * Authorization: Bearer &lt;access token&gt;      Content-Type: application/json
 * { "md5": "d60f3db96913029a2af979a1662c1e72" }
 *
 * 200 paid:      { "responseCode": 0, "responseMessage": "Getting transaction successfully.",
 *                  "data": { "hash", "fromAccountId", "toAccountId", "currency", "amount", "description" } }
 * 200 not found: { "responseCode": 1, "errorCode": 1, "data": null, "responseMessage": "Transaction could not be found…" }
 * 200 failed:    { "responseCode": 1, "errorCode": 3, "data": null, "responseMessage": "Transaction failed." }
 * </pre>
 * {@code data.hash} is the bank transaction reference. {@code amount} is documented as a String but the sample
 * shows a number, so both are accepted. The documented response has no payment time; the live API also sends
 * {@code acknowledgedDateMs} / {@code createdDateMs} (epoch ms), used when present (else the processor uses now).
 * <p>
 * Never throws: not found, failed, HTTP errors, timeouts and unreadable answers all mean "not paid yet", so the
 * check job simply tries again. The token (from {@code BAKONG_API_TOKEN}; it expires and is renewed at
 * {@code /v1/renew_token} by hand) is never logged.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.payment.bakong", name = "mode", havingValue = "bakong")
public class BakongHttpClient implements BakongClient {

    static final String CHECK_BY_MD5 = "/v1/check_transaction_by_md5";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    @Autowired
    public BakongHttpClient(RestClient.Builder builder, PaymentProperties properties) {
        this(configure(builder, properties.bakong()));
    }

    /** Tests: a RestClient already bound to a mock server (base URL + token set). */
    BakongHttpClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient configure(RestClient.Builder builder, PaymentProperties.Bakong bakong) {
        if (bakong.token() == null || bakong.token().isBlank()) {
            throw new IllegalStateException("app.payment.bakong.mode=bakong needs BAKONG_API_TOKEN");
        }
        Duration timeout = bakong.timeout() != null ? bakong.timeout() : DEFAULT_TIMEOUT;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return builder
                .baseUrl(bakong.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bakong.token())
                .build();
    }

    @Override
    public Optional<GatewayPayment> findPaid(String qrMd5) {
        Map<String, Object> body;
        try {
            body = restClient.post()
                    .uri(CHECK_BY_MD5)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("md5", qrMd5))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                log.error("Bakong rejected the API token (HTTP 401): renew BAKONG_API_TOKEN");
            } else {
                log.warn("Bakong check failed: HTTP {}", ex.getStatusCode().value());
            }
            return Optional.empty();
        } catch (RestClientException ex) {
            log.warn("Bakong unreachable: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
        return toPayment(body);
    }

    static Optional<GatewayPayment> toPayment(Map<String, Object> body) {
        if (body == null || !Integer.valueOf(0).equals(asInt(body.get("responseCode")))
                || !(body.get("data") instanceof Map<?, ?> data)) {
            // errorCode 1 = not found (not paid yet), 3 = failed: both "not paid"
            return Optional.empty();
        }
        Object hash = data.get("hash");
        BigDecimal amount = asDecimal(data.get("amount"));
        Object currency = data.get("currency");
        if (hash == null || amount == null || currency == null) {
            log.warn("Bakong answered success without hash / amount / currency; ignored");
            return Optional.empty();
        }
        return Optional.of(new GatewayPayment(hash.toString(), amount, currency.toString(),
                paidAt(data), body));
    }

    private static Instant paidAt(Map<?, ?> data) {
        Long ms = asLong(data.get("acknowledgedDateMs"));
        if (ms == null) {
            ms = asLong(data.get("createdDateMs"));
        }
        return ms == null ? null : Instant.ofEpochMilli(ms);
    }

    private static Integer asInt(Object value) {
        return value instanceof Number number ? Integer.valueOf(number.intValue()) : null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? Long.valueOf(number.longValue()) : null;
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            // String.valueOf(1.0) = "1.0": no binary fraction noise from the JSON double
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
