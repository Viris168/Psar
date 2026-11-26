package com.psar.common.exception;

/** 404 - resource missing, or not owned by the current user (never reveal which). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
