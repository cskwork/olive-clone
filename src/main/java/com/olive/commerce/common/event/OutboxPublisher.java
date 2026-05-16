package com.olive.commerce.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 아웃박스 이벤트 퍼블리셔 (PRD §12, wiki §96-eventing).
 * <p>도메인 서비스에서 아웅박스 이벤트를 발행할 때 사용합니다.
 * <p>호출하는 트랜잭션 내에서 실행되어야 하며, 별도의 @Transactional이 없어서
 * 호출자의 트랜잭션에 참여합니다.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 아웃박스 이벤트를 발행합니다.
     * <p>호출자의 트랜잭션 내에서 실행되어야 하며, 트랜잭션 커밋 시 함께 커밋됩니다.
     * <p>본 메서드는 @Transactional이 아니므로 독립 트랜잭션을 생성하지 않습니다.
     *
     * @param aggregateType 집계 타입 (예: PAYMENT, ORDER, DELIVERY)
     * @param aggregateId   집계 ID (예: payment.id, order.id)
     * @param eventType     이벤트 타입 (예: PAYMENT_APPROVED, ORDER_CANCELED)
     * @param payload       페이로드 데이터
     */
    public void publish(String aggregateType, Long aggregateId, String eventType, Map<String, Object> payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            OutboxEvent outboxEvent = OutboxEvent.create(aggregateType, aggregateId, eventType, payloadJson);
            outboxEventRepository.save(outboxEvent);
            log.debug("Outbox event published: type={}, aggregateType={}, aggregateId={}",
                    eventType, aggregateType, aggregateId);
        } catch (Exception e) {
            log.error("Failed to publish outbox event: type={}, aggregateType={}, aggregateId={}",
                    eventType, aggregateType, aggregateId, e);
            throw new RuntimeException("Outbox event publish failed", e);
        }
    }

    /**
     * 아웃박스 이벤트를 발행합니다 (편의 메서드).
     *
     * @param eventType 이벤트 타입 (예: PAYMENT_APPROVED, ORDER_CANCELED)
     * @param aggregateType 집계 타입
     * @param aggregateId   집계 ID
     * @param payload       페이로드 데이터
     */
    public void publish(String eventType, String aggregateType, Long aggregateId, Map<String, Object> payload) {
        publish(aggregateType, aggregateId, eventType, payload);
    }
}
