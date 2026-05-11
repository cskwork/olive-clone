package com.olive.commerce.search;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 비동기 outbox 큐 row (wiki §96-eventing, PRD §12).
 *
 * <p>도메인 쓰기 트랜잭션이 같은 트랜잭션 안에서 PENDING 상태로 insert.
 * {@link OutboxIndexerWorker}가 SELECT FOR UPDATE SKIP LOCKED로 픽업하여
 * 외부 시스템(현재는 OpenSearch) 호출 → DONE 또는 attempt_count+1 (5회
 * 도달 시 dlq=true). row 자체는 도메인 메서드로만 변이된다(필드 직접 setter X).
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "status", nullable = false, length = 20)
    private String status = OutboxStatus.PENDING.name();

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "dlq", nullable = false)
    private boolean dlq = false;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    protected OutboxEvent() {}

    private OutboxEvent(String aggregateType, Long aggregateId, String eventType, String payloadJson) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
    }

    public static OutboxEvent productIndexSync(Long productId, String payloadJson) {
        return new OutboxEvent("PRODUCT", productId, "PRODUCT_INDEX_SYNC", payloadJson);
    }

    public static OutboxEvent create(String aggregateType, Long aggregateId, String eventType, String payloadJson) {
        return new OutboxEvent(aggregateType, aggregateId, eventType, payloadJson);
    }

    public void markInProgress() {
        this.status = OutboxStatus.IN_PROGRESS.name();
    }

    public void markDone() {
        this.status = OutboxStatus.DONE.name();
        this.processedAt = OffsetDateTime.now();
        this.lastError = null;
    }

    public void markFailure(String errorMessage) {
        this.attemptCount += 1;
        this.lastError = errorMessage;
        if (this.attemptCount >= MAX_ATTEMPTS) {
            this.dlq = true;
            this.status = OutboxStatus.FAILED.name();
            this.processedAt = OffsetDateTime.now();
        } else {
            this.status = OutboxStatus.PENDING.name();
        }
    }

    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public Long getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayloadJson() { return payloadJson; }
    public OutboxStatus getStatus() { return OutboxStatus.valueOf(status); }
    public int getAttemptCount() { return attemptCount; }
    public boolean isDlq() { return dlq; }
    public String getLastError() { return lastError; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getProcessedAt() { return processedAt; }

    public enum OutboxStatus {
        PENDING, IN_PROGRESS, DONE, FAILED
    }
}
