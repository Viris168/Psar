package com.psar.payment.dto.response;

/** Answer to a signed webhook call: always 200, whatever the outcome, so the sender stops retrying. */
public record WebhookAckResponse(String outcome) {
}
