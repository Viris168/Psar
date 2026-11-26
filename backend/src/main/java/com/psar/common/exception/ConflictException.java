package com.psar.common.exception;

/** 409 - state conflict, e.g. not enough stock or duplicate email. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
