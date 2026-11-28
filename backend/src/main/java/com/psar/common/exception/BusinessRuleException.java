package com.psar.common.exception;

import java.util.Map;

/** 422 - request is valid but breaks a business rule, e.g. COD for a province order. */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String message) {
        super(ErrorCode.BUSINESS_RULE, message);
    }

    public BusinessRuleException(String message, Map<String, String> details) {
        super(ErrorCode.BUSINESS_RULE, message, details);
    }

    /** A more specific 422 code, e.g. {@link ErrorCode#COUPON_INVALID}. */
    public BusinessRuleException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
