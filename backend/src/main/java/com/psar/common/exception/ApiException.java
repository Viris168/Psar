package com.psar.common.exception;

import lombok.Getter;

import java.util.Map;

/**
 * Base for every domain exception. GlobalExceptionHandler turns it into
 * an ErrorResponse using the code's HTTP status.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, String> details;

    protected ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    protected ApiException(ErrorCode errorCode, String message, Map<String, String> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }
}
