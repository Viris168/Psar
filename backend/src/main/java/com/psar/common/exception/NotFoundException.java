package com.psar.common.exception;

/** 404 - resource missing, or not owned by the current user (never reveal which). */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }

    /** e.g. {@code NotFoundException.of("Address")} -> "Address not found". */
    public static NotFoundException of(String resource) {
        return new NotFoundException(resource + " not found");
    }
}
