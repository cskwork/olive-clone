package com.olive.commerce.product;

import java.time.Instant;

/**
 * 상품 변경 이벤트 (PRD §96 eventing).
 *
 * Admin service에서 발행하면 Public service의 listener가 cache 무효화.
 * transaction-after-commit에 실행되어 DB 반영 보장 후 cache 삭제.
 */
public record ProductUpdatedEvent(
    Long productId,
    Instant occurredAt
) {
    public ProductUpdatedEvent(Long productId) {
        this(productId, Instant.now());
    }
}
