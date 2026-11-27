package com.psar.payment.service.bakong;

import com.psar.payment.config.PaymentProperties;
import com.psar.payment.gateway.GatewayPayment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Bakong "check transaction by md5" contract (responses as in the Open API document v1.0.2, §5). */
class BakongHttpClientTest {

    private static final String BASE = "https://bakong.test";
    private static final String MD5 = "d60f3db96913029a2af979a1662c1e72";

    private MockRestServiceServer server;
    private BakongHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BakongHttpClient(builder.build());
    }

    @Test
    void findPaid_paid_returnsHashAmountCurrency() {
        server.expect(requestTo(BASE + "/v1/check_transaction_by_md5"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(content().json("{\"md5\":\"" + MD5 + "\"}", true))
                .andRespond(withSuccess("""
                        {"responseCode": 0, "responseMessage": "Getting transaction successfully.", "errorCode": null,
                         "data": {"hash": "8465d722d7d5065f", "fromAccountId": "rieu_dhqj_1984@devb",
                                  "toAccountId": "psar_shop@devb", "currency": "USD", "amount": 10.01,
                                  "description": "ORD-1", "acknowledgedDateMs": 1767225600000}}""",
                        MediaType.APPLICATION_JSON));

        Optional<GatewayPayment> paid = client.findPaid(MD5);

        server.verify();
        assertThat(paid).get().satisfies(p -> {
            assertThat(p.transactionRef()).isEqualTo("8465d722d7d5065f");
            assertThat(p.amount()).isEqualByComparingTo("10.01");
            assertThat(p.currency()).isEqualTo("USD");
            assertThat(p.paidAt()).isEqualTo(Instant.ofEpochMilli(1767225600000L));
            assertThat(p.raw()).containsKey("data");
        });
    }

    @Test
    void findPaid_amountAsString_parsed() {
        server.expect(requestTo(BASE + "/v1/check_transaction_by_md5")).andRespond(withSuccess("""
                {"responseCode": 0, "data": {"hash": "abc", "currency": "USD", "amount": "1.50"}}""",
                MediaType.APPLICATION_JSON));

        assertThat(client.findPaid(MD5)).get().satisfies(p -> {
            assertThat(p.amount()).isEqualByComparingTo("1.50");
            assertThat(p.paidAt()).isNull();
        });
    }

    @Test
    void findPaid_notFound_empty() {
        server.expect(requestTo(BASE + "/v1/check_transaction_by_md5")).andRespond(withSuccess("""
                {"data": null, "errorCode": 1, "responseCode": 1,
                 "responseMessage": "Transaction could not be found. Please check and try again."}""",
                MediaType.APPLICATION_JSON));

        assertThat(client.findPaid(MD5)).isEmpty();
    }

    @Test
    void findPaid_failedTransaction_empty() {
        server.expect(requestTo(BASE + "/v1/check_transaction_by_md5")).andRespond(withSuccess("""
                {"data": null, "errorCode": 3, "responseCode": 1, "responseMessage": "Transaction failed."}""",
                MediaType.APPLICATION_JSON));

        assertThat(client.findPaid(MD5)).isEmpty();
    }

    @Test
    void findPaid_serverErrorOrExpiredToken_empty() {
        server.expect(requestTo(BASE + "/v1/check_transaction_by_md5"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(client.findPaid(MD5)).isEmpty();

        server.reset();
        server.expect(requestTo(BASE + "/v1/check_transaction_by_md5")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(client.findPaid(MD5)).isEmpty();
    }

    @Test
    void findPaid_notJson_empty() {
        server.expect(requestTo(BASE + "/v1/check_transaction_by_md5"))
                .andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));

        assertThat(client.findPaid(MD5)).isEmpty();
    }

    @Test
    void findPaid_unreachable_empty() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        BakongHttpClient real = new BakongHttpClient(RestClient.builder(), properties("http://127.0.0.1:" + closedPort,
                "token"));

        assertThat(real.findPaid(MD5)).isEmpty();
    }

    @Test
    void constructor_bakongModeWithoutToken_failsFast() {
        assertThatThrownBy(() -> new BakongHttpClient(RestClient.builder(), properties(BASE, " ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BAKONG_API_TOKEN");
    }

    private static PaymentProperties properties(String baseUrl, String token) {
        return new PaymentProperties(null, new PaymentProperties.Bakong("bakong", baseUrl, token,
                Duration.ofSeconds(1)), null, null, null);
    }
}
