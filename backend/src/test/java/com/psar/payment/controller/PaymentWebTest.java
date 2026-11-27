package com.psar.payment.controller;

import com.psar.support.IntegrationTest;
import org.springframework.test.context.TestPropertySource;

/**
 * The webhook and simulate endpoints need a webhook secret and the simulate switch on, which the shared test
 * context doesn't have: their tests share this one extra context (same properties → one cached context).
 */
@TestPropertySource(properties = {
        "app.payment.webhook.secret=" + PaymentWebTest.WEBHOOK_SECRET,
        "app.payment.simulate.enabled=true"
})
abstract class PaymentWebTest extends IntegrationTest {

    static final String WEBHOOK_SECRET = "test-webhook-secret";
}
