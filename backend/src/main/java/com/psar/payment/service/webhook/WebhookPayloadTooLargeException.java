package com.psar.payment.service.webhook;

import com.psar.common.exception.ApiException;
import com.psar.common.exception.ErrorCode;

/** 413 - a webhook body over {@link PaymentWebhookService#MAX_BODY_BYTES}. */
public class WebhookPayloadTooLargeException extends ApiException {

    public WebhookPayloadTooLargeException() {
        super(ErrorCode.PAYLOAD_TOO_LARGE, "Webhook body is larger than "
                + PaymentWebhookService.MAX_BODY_BYTES / 1024 + " KB");
    }
}
