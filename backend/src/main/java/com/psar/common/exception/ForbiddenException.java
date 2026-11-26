package com.psar.common.exception;

/** 403 - authenticated but not allowed, e.g. a disabled account. */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }

    public ForbiddenException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static ForbiddenException accountDisabled() {
        return new ForbiddenException(ErrorCode.ACCOUNT_DISABLED, "This account is disabled");
    }
}
