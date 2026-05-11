package com.olive.commerce.search;

/**
 * OpenSearch bulk 또는 단건 인덱싱이 실패했을 때 {@link ProductIndexer}가 던지는 예외.
 * 호출자(워커/어드민)는 attempt_count 증가 또는 사용자 응답으로 변환한다.
 */
public class IndexingException extends RuntimeException {
    public IndexingException(String message) {
        super(message);
    }

    public IndexingException(String message, Throwable cause) {
        super(message, cause);
    }
}
