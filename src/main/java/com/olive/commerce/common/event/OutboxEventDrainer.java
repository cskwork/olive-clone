package com.olive.commerce.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.delivery.DeliveryCompletedEvent;
import com.olive.commerce.inventory.InventoryCommitFailedEvent;
import com.olive.commerce.payment.OrderRefundedEvent;
import com.olive.commerce.payment.PaymentApprovedEvent;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 아웃박스 이벤트 드레이너 (PRD §12, wiki §96-eventing).
 * <p>
 * 1초 주기로 PENDING 상태의 아웃박스 이벤트를 처리하여 Spring ApplicationEvent로 발행합니다.
 * 멀티 인스턴스 배포 환경에서 안전하게 동작하도록 SELECT FOR UPDATE SKIP LOCKED를 사용합니다.
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>PENDING 상태의 이벤트를 배치로 조회 (IN_PROGRESS로 변경)</li>
 *   <li>각 이벤트 타입에 해당하는 Spring ApplicationEvent 발행</li>
 *   <li>성공 시 DONE, 실패 시 attempt_count 증가 (5회 도달 시 FAILED)</li>
 * </ol>
 */
@Component
public class OutboxEventDrainer {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventDrainer.class);
    private static final int BATCH_SIZE = 100;
    private static final List<String> SUPPORTED_EVENT_TYPES = List.of(
            "PAYMENT_APPROVED",
            "ORDER_CANCELED",
            "ORDER_REFUNDED",
            "DELIVERY_COMPLETED",
            "INVENTORY_COMMIT_FAILED"
    );

    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public OutboxEventDrainer(
            OutboxEventRepository outboxEventRepository,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 1초 fixed-delay 폴링.
     */
    @Scheduled(fixedDelay = 1000)
    public void drain() {
        try {
            drainOnce();
        } catch (Exception e) {
            log.warn("OutboxEventDrainer drain failed (will retry next tick)", e);
        }
    }

    /**
     * 단일 드레인 실행 (테스트용).
     *
     * @return 처리한 이벤트 수
     */
    public int drainOnce() {
        List<OutboxEvent> claimed = claimBatch();
        if (claimed.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (OutboxEvent event : claimed) {
            try {
                publishEvent(event);
                finalizeEvent(event.getId(), true, null);
                processed++;
            } catch (Exception e) {
                log.warn("Failed to process outbox event: id={}, type={}", event.getId(), event.getEventType(), e);
                finalizeEvent(event.getId(), false, e.getMessage());
            }
        }

        return processed;
    }

    /**
     * 1단계 트랜잭션: PENDING → IN_PROGRESS로 marking + 락 획득.
     */
    private List<OutboxEvent> claimBatch() {
        return txTemplate.execute(status -> {
            List<OutboxEvent> rows = outboxEventRepository.findPendingBatchByEventTypeIn(
                    SUPPORTED_EVENT_TYPES,
                    PageRequest.of(0, BATCH_SIZE)
            );
            for (OutboxEvent row : rows) {
                row.markInProgress();
            }
            return rows;
        });
    }

    /**
     * 아웃박스 이벤트를 Spring ApplicationEvent로 발행합니다.
     */
    private void publishEvent(OutboxEvent outboxEvent) {
        String eventType = outboxEvent.getEventType();
        String payloadJson = outboxEvent.getPayloadJson();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);

            switch (eventType) {
                case "PAYMENT_APPROVED" -> {
                    PaymentApprovedEvent event = new PaymentApprovedEvent(
                            this,
                            getLong(payload, "orderId"),
                            getString(payload, "orderNo"),
                            getLong(payload, "paymentId"),
                            getString(payload, "paymentKey"),
                            getBigDecimal(payload, "approvedAmount")
                    );
                    eventPublisher.publishEvent(event);
                }

                case "ORDER_CANCELED" -> {
                    com.olive.commerce.order.OrderCanceledEvent event = new com.olive.commerce.order.OrderCanceledEvent(
                            this,
                            getLong(payload, "orderId"),
                            getString(payload, "orderNo"),
                            getLong(payload, "memberId"),
                            getString(payload, "reason"),
                            getOrderStatus(payload, "fromStatus"),
                            getCancelKind(payload, "cancelKind")
                    );
                    eventPublisher.publishEvent(event);
                }

                case "ORDER_REFUNDED" -> {
                    OrderRefundedEvent event = new OrderRefundedEvent(
                            this,
                            getLong(payload, "refundId"),
                            getLong(payload, "orderId"),
                            getString(payload, "orderNo"),
                            getLong(payload, "paymentId"),
                            getBigDecimal(payload, "amount")
                    );
                    eventPublisher.publishEvent(event);
                }

                case "DELIVERY_COMPLETED" -> {
                    DeliveryCompletedEvent event = new DeliveryCompletedEvent(
                            this,
                            getLong(payload, "deliveryId"),
                            getLong(payload, "orderId"),
                            getString(payload, "orderNo"),
                            getLong(payload, "memberId"),
                            getString(payload, "invoiceNo")
                    );
                    eventPublisher.publishEvent(event);
                }

                case "INVENTORY_COMMIT_FAILED" -> {
                    InventoryCommitFailedEvent event = new InventoryCommitFailedEvent(
                            this,
                            getLong(payload, "orderId")
                    );
                    eventPublisher.publishEvent(event);
                }

                default -> {
                    log.debug("Unknown event type: {}", eventType);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish event: type=" + eventType, e);
        }
    }

    /**
     * 2단계 트랜잭션: 처리 결과 반영.
     */
    private void finalizeEvent(Long eventId, boolean success, String errorMessage) {
        txTemplate.executeWithoutResult(status -> {
            OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
            if (event == null) {
                log.warn("Event not found for finalization: id={}", eventId);
                return;
            }

            if (success) {
                event.markDone();
            } else {
                event.markFailure(errorMessage);
                if (event.getAttemptCount() >= OutboxEvent.MAX_ATTEMPTS) {
                    log.error("Outbox event moved to DLQ: id={}, type={}, attempts={}",
                            eventId, event.getEventType(), event.getAttemptCount());
                }
            }
        });
    }

    // Helper methods

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Long getLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private java.math.BigDecimal getBigDecimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return new java.math.BigDecimal(value.toString());
        }
        return null;
    }

    private com.olive.commerce.order.Order.OrderStatus getOrderStatus(Map<String, Object> map, String key) {
        String value = getString(map, key);
        return value != null ? com.olive.commerce.order.Order.OrderStatus.valueOf(value) : null;
    }

    private com.olive.commerce.order.OrderCanceledEvent.CancelKind getCancelKind(Map<String, Object> map, String key) {
        String value = getString(map, key);
        return value != null ? com.olive.commerce.order.OrderCanceledEvent.CancelKind.valueOf(value) : null;
    }
}
