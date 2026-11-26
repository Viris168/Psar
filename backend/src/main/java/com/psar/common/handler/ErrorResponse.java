package com.psar.common.handler;

import com.psar.common.exception.ErrorCode;

import java.time.Instant;
import java.util.Map;

/**
 * Consistent error body returned by every endpoint.
 */
public record ErrorResponse(
        String code,
        String message,
        Map<String, String> details,
        Instant timestamp
) {
    public static ErrorResponse of(ErrorCode code, String message) {
        return of(code, message, Map.of());
    }

    public static ErrorResponse of(ErrorCode code, String message, Map<String, String> details) {
        return new ErrorResponse(code.name(), message, details, Instant.now());
    }
}
