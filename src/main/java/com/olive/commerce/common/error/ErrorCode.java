package com.olive.commerce.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND),
    PRODUCT_SOLD_OUT(HttpStatus.CONFLICT),
    COUPON_INVALID(HttpStatus.BAD_REQUEST),
    INSUFFICIENT_INVENTORY(HttpStatus.CONFLICT),
    LOCK_ACQUISITION_FAILED(HttpStatus.CONFLICT),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    EMAIL_ALREADY_USED(HttpStatus.CONFLICT),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(HttpStatus.LOCKED),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED),
    BRAND_SLUG_DUPLICATE(HttpStatus.CONFLICT),
    BRAND_NOT_FOUND(HttpStatus.NOT_FOUND),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND),
    CATEGORY_HAS_PRODUCTS(HttpStatus.CONFLICT),
    CATEGORY_CYCLE_DETECTED(HttpStatus.BAD_REQUEST),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND),
    PRODUCT_OPTION_NOT_FOUND(HttpStatus.NOT_FOUND),
    INVALID_PRODUCT_STATE_TRANSITION(HttpStatus.UNPROCESSABLE_ENTITY),
    FILE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST),
    INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
