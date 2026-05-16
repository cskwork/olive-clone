package com.olive.commerce.delivery.client;

/**
 * 택배사 클라이언트 예외.
 */
public class CarrierClientException extends RuntimeException {

    private final boolean retryable;

    public CarrierClientException(String message) {
        super(message);
        this.retryable = true;
    }

    public CarrierClientException(String message, Throwable cause) {
        super(message, cause);
        this.retryable = true;
    }

    public CarrierClientException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public CarrierClientException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * 재시도 가능한 예부인지 확인합니다.
     *
     * @return true면 재시도 큐에 등록, false면 즉시 DEAD 처리
     */
    public boolean isRetryable() {
        return retryable;
    }
}
