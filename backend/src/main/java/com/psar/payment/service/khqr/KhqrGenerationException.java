package com.psar.payment.service.khqr;

/**
 * The SDK could not make a KHQR code (configuration or SDK error). Unexpected, so it surfaces as a 500
 * ({@code INTERNAL_ERROR}) and rolls the checkout back.
 */
public class KhqrGenerationException extends IllegalStateException {

    public KhqrGenerationException(String message) {
        super(message);
    }

    public KhqrGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
