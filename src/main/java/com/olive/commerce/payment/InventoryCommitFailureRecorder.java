package com.olive.commerce.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.order.Order;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 재고 커밋 실패 보정 이벤트 기록 컴포넌트.
 *
 * <p>PG 승인은 이미 확정되었으므로(order=PAID, payment=APPROVED) 재고 커밋 실패는
 * 결제 트랜잭션을 롤백하지 않고 INVENTORY_COMMIT_FAILED outbox 이벤트로 남겨 배치/워커가
 * 재시도하게 한다. 이 이벤트가 결제 트랜잭션과 함께 사라지지 않도록
 * {@link Propagation#REQUIRES_NEW}로 독립 커밋한다 — 별도 빈에 두어야 Spring 프록시가
 * 새 트랜잭션을 실제로 시작한다(self-invocation은 인터셉트되지 않음).
 */
@Component
@RequiredArgsConstructor
public class InventoryCommitFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(InventoryCommitFailureRecorder.class);

    private final OutboxEventRepository outboxEventRepository;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    /**
     * 재고 커밋 실패를 독립 트랜잭션으로 영속화한다.
     * <p>
     * outbox insert가 실패하더라도 보정 신호가 조용히 사라지지 않도록 구조화된 ERROR
     * 알림 로그를 함께 남긴다. 이 메서드는 호출자 트랜잭션과 분리되어 커밋되므로,
     * 이후 결제 트랜잭션이 롤백되어도 보정 이벤트는 유지된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Order order, Payment payment, Exception cause) {
        String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        try {
            String payloadJson = objectMapper.writeValueAsString(Map.of(
                    "orderId", order.getId(),
                    "orderNo", order.getOrderNo(),
                    "paymentId", payment.getId(),
                    "reason", reason
            ));
            OutboxEvent outboxEvent = OutboxEvent.create(
                    "PAYMENT",
                    payment.getId(),
                    "INVENTORY_COMMIT_FAILED",
                    payloadJson
            );
            outboxEventRepository.save(outboxEvent);
            auditLogger.log("INVENTORY_COMMIT_FAILED", Map.of(
                    "orderId", order.getId(),
                    "orderNo", order.getOrderNo(),
                    "paymentId", payment.getId()
            ));
        } catch (Exception e) {
            // 마지막 방어선: outbox 영속화조차 실패하면 보정 신호가 사라진다.
            // 운영 알림이 걸리도록 구조화된 ERROR 로그를 남기고 예외를 전파해
            // REQUIRES_NEW 트랜잭션을 롤백시킨다(호출자 결제 트랜잭션은 영향 없음).
            log.error("ALERT inventory-commit compensation event could NOT be persisted — manual recovery required. "
                            + "orderId={}, orderNo={}, paymentId={}, reason={}",
                    order.getId(), order.getOrderNo(), payment.getId(), reason, e);
            throw new IllegalStateException(
                    "Failed to persist INVENTORY_COMMIT_FAILED event for payment " + payment.getId(), e);
        }
    }
}
