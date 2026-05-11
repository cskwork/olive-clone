package com.olive.commerce.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 도메인 쓰기 트랜잭션 안에서 outbox row를 enqueue하는 헬퍼.
 *
 * <p>{@link com.olive.commerce.product.ProductAdminService}는 상품을 변경한 후
 * 같은 트랜잭션에서 본 메서드를 호출 — wiki §96-eventing의 "Producer pattern"을
 * 정확히 따른다. {@code ApplicationEventPublisher}는 캐시 무효화 listener
 * (OLV-023)가 받아 쓰던 그대로 두고, 본 컴포넌트는 검색 인덱스 동기화 경로만
 * 책임진다.
 */
@Component
public class ProductIndexEnqueuer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OutboxEventRepository outboxRepository;

    public ProductIndexEnqueuer(OutboxEventRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /**
     * 호출자의 활성 트랜잭션에 outbox row를 insert (별도 transaction 시작 X).
     * 트랜잭션이 롤백되면 본 row도 자동 롤백 — at-least-once 약속의 핵심.
     */
    public OutboxEvent enqueueProductIndexSync(Long productId) {
        String payload = toPayload(productId);
        OutboxEvent event = OutboxEvent.productIndexSync(productId, payload);
        return outboxRepository.save(event);
    }

    private String toPayload(Long productId) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("productId", productId));
        } catch (JsonProcessingException e) {
            // Map 직렬화 실패는 사실상 불가능 — 그래도 안전 디폴트.
            return "{\"productId\":" + productId + "}";
        }
    }
}
