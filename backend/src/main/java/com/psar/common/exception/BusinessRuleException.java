package com.psar.common.exception;

/** 422 - request is valid but breaks a business rule, e.g. COD for a province order. */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
