package com.psar.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Every error the API can return. The name is the stable "code" the frontend switches on
 * (and translates); the message is for humans and may change.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),

    // 401
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED),

    // 403
    FORBIDDEN(HttpStatus.FORBIDDEN),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN),

    // 404 / 405 / 415
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    // 409
    CONFLICT(HttpStatus.CONFLICT),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT),
    SKU_ALREADY_EXISTS(HttpStatus.CONFLICT),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT),
    /** e.g. receiving a purchase order that is already received or cancelled. */
    INVALID_STATUS(HttpStatus.CONFLICT),
    /** A promotion's usage limit was reached while checking out (the whole checkout rolls back). */
    PROMOTION_EXHAUSTED(HttpStatus.CONFLICT),

    // 413
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),

    // 422
    BUSINESS_RULE(HttpStatus.UNPROCESSABLE_ENTITY),
    /** Checkout with a coupon that can't be used (unknown, ended, used up, below its minimum, …). */
    COUPON_INVALID(HttpStatus.UNPROCESSABLE_ENTITY),

    // 500
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;
}
