package com.olive.commerce.payment;

import com.olive.commerce.order.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 결제 엔티티 (PRD §7.7, V9__payment.sql).
 * <p>
 * 상태 전이: READY → REQUESTED → APPROVED
 * <p>실패/취소/환불로도 전이 가능.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(name = "payment_key", length = 255)
    private String paymentKey;

    @Column(name = "pg_provider", length = 50)
    private String pgProvider;

    @Column(name = "method", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.READY;

    @Column(name = "requested_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal requestedAmount;

    @Column(name = "approved_amount", precision = 12, scale = 2)
    private BigDecimal approvedAmount;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "requested_at", insertable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "failed_reason", length = 255)
    private String failedReason;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected Payment() {}

    private Payment(Order order, PaymentMethod method, BigDecimal requestedAmount, UUID idempotencyKey) {
        this.order = order;
        this.method = method;
        this.requestedAmount = requestedAmount;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.REQUESTED;
    }

    /**
     * 결제 요청 상태로 생성.
     */
    public static Payment createRequest(Order order, PaymentMethod method, BigDecimal requestedAmount, UUID idempotencyKey) {
        return new Payment(order, method, requestedAmount, idempotencyKey);
    }

    /**
     * PG 승인 성공 처리.
     */
    public void approve(String paymentKey, String pgProvider, BigDecimal approvedAmount, OffsetDateTime approvedAt) {
        if (this.status != PaymentStatus.REQUESTED) {
            throw new IllegalStateException(
                String.format("Cannot approve payment in status: %s", this.status));
        }
        this.status = PaymentStatus.APPROVED;
        this.paymentKey = paymentKey;
        this.pgProvider = pgProvider;
        this.approvedAmount = approvedAmount;
        this.approvedAt = approvedAt;
    }

    /**
     * PG 실패 처리.
     */
    public void fail(String failedReason) {
        if (this.status != PaymentStatus.REQUESTED) {
            throw new IllegalStateException(
                String.format("Cannot fail payment in status: %s", this.status));
        }
        this.status = PaymentStatus.FAILED;
        this.failedReason = failedReason;
    }

    /**
     * 웹훅 등 외부에서 상태 직접 변경 (OLV-073).
     * <p>
     * 주의: 내부 상태 전이 규칙은 검증하지 않음.
     * 호출자가 유효한 상태 전임을 보장해야 함.
     */
    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    /**
     * 실패 사유 설정.
     */
    public void setFailedReason(String failedReason) {
        this.failedReason = failedReason;
    }

    // Getters
    public Long getId() { return id; }

    /**
     * 테스트용 ID 설정자.
     */
    public void setId(Long id) {
        this.id = id;
    }
    public Order getOrder() { return order; }
    public String getPaymentKey() { return paymentKey; }
    public String getPgProvider() { return pgProvider; }
    public PaymentMethod getMethod() { return method; }
    public PaymentStatus getStatus() { return status; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public String getFailedReason() { return failedReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /**
     * 결제 상태 (PRD §6.6).
     */
    public enum PaymentStatus {
        READY,      // 결제 준비
        REQUESTED,  // 결제 요청
        APPROVED,   // 결제 승인
        FAILED,     // 결제 실패
        CANCELED,   // 결제 취소
        REFUNDED    // 환불 완료
    }

    /**
     * 결제 수단.
     */
    public enum PaymentMethod {
        CARD,
        KAKAO_PAY,
        NAVER_PAY,
        TOSS_PAY,
        VIRTUAL_ACCOUNT
    }
}
