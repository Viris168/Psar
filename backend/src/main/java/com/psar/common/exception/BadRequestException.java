package com.psar.common.exception;

import java.util.Map;

/** 400 - input that Bean Validation can't catch, e.g. a CITY pin outside Phnom Penh. */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(ErrorCode.BAD_REQUEST, message);
    }

    public BadRequestException(String message, Map<String, String> details) {
        super(ErrorCode.BAD_REQUEST, message, details);
    }
}
