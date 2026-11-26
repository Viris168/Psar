package com.psar.common.exception;

/** 401 - wrong credentials, or an expired/revoked/unknown token. */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }

    public UnauthorizedException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static UnauthorizedException invalidCredentials() {
        return new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
    }

    public static UnauthorizedException invalidToken() {
        return new UnauthorizedException(ErrorCode.INVALID_TOKEN, "Token is invalid or expired");
    }
}
