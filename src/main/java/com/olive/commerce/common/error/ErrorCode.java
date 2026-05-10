package com.olive.commerce.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND),
    PRODUCT_SOLD_OUT(HttpStatus.CONFLICT),
    COUPON_INVALID(HttpStatus.BAD_REQUEST),
    INSUFFICIENT_INVENTORY(HttpStatus.CONFLICT),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
