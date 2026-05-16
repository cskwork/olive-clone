package com.olive.commerce.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 배송 택배사 API 실패 재시도 큐 엔티티 (PRD §15.2).
 * <p>
 * 택배사 API 호출 실패 시 이 테이블에 기록되고,
 * 스케줄러 또는 관리자가 수동으로 재시도합니다.
 */
@Entity
@Table(name = "delivery_retry_queue")
public class DeliveryRetryQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    @Column(name = "request_kind", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private RequestKind requestKind;

    @Column(name = "payload_json", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payloadJson;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "next_retry_at", nullable = false)
    private OffsetDateTime nextRetryAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private QueueStatus status = QueueStatus.PENDING;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected DeliveryRetryQueue() {}

    private DeliveryRetryQueue(
            Delivery delivery,
            RequestKind requestKind,
            String payloadJson,
            OffsetDateTime nextRetryAt
    ) {
        this.delivery = delivery;
        this.requestKind = requestKind;
        this.payloadJson = payloadJson;
        this.nextRetryAt = nextRetryAt;
    }

    public static DeliveryRetryQueue create(
            Delivery delivery,
            RequestKind requestKind,
            String payloadJson,
            OffsetDateTime nextRetryAt
    ) {
        return new DeliveryRetryQueue(delivery, requestKind, payloadJson, nextRetryAt);
    }

    /**
     * 재시드 횟수를 증가시키고 다음 재시도 시간을 설정합니다.
     *
     * @return 최대 재시도 횟수(5)를 초과하면 true
     */
    public boolean incrementRetry(OffsetDateTime nextRetryAt, String error) {
        this.retryCount++;
        this.nextRetryAt = nextRetryAt;
        this.lastError = error;
        return this.retryCount >= 5;
    }

    /**
     * 재시도 큐 항목을 완료 상태로 변경합니다.
     */
    public void markDone() {
        this.status = QueueStatus.DONE;
    }

    /**
     * 재시도 큐 항목을 데드레터 상태로 변경합니다.
     */
    public void markDead() {
        this.status = QueueStatus.DEAD;
    }

    // Getters
    public Long getId() { return id; }
    public Delivery getDelivery() { return delivery; }
    public RequestKind getRequestKind() { return requestKind; }
    public String getPayloadJson() { return payloadJson; }
    public Integer getRetryCount() { return retryCount; }
    public OffsetDateTime getNextRetryAt() { return nextRetryAt; }
    public String getLastError() { return lastError; }
    public QueueStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /**
     * 재시도 요청 종류.
     */
    public enum RequestKind {
        ISSUE_INVOICE,  // 운송장 발급
        FETCH_STATUS     // 상태 조회
    }

    /**
     * 재시도 큐 상태.
     */
    public enum QueueStatus {
        PENDING,  // 재시도 대기중
        DONE,     // 성공 완료
        DEAD      // 최대 재시도 초과 (관리자 수동 처리 필요)
    }
}
