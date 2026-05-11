package com.olive.commerce.payment.client.exception;

/**
 * PG 요청 타임아웃 예외.
 */
public class PgTimeoutException extends RuntimeException {

    public PgTimeoutException(String message) {
        super(message);
    }

    public PgTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
