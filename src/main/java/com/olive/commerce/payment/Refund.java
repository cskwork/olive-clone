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

/**
 * 환불 엔티티 (PRD §7.7, V9__payment.sql).
 * <p>
 * 상태 전이: REQUESTED → APPROVED or FAILED
 */
@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @OneToOne
    @JoinColumn(name = "order_id", nullable = false, insertable = false, updatable = false)
    private Order order;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private RefundStatus status = RefundStatus.REQUESTED;

    @Column(name = "pg_refund_key", length = 255)
    private String pgRefundKey;

    @Column(name = "requested_at", insertable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "failed_reason", length = 255)
    private String failedReason;

    protected Refund() {}

    private Refund(Payment payment, Order order, BigDecimal amount, String reason) {
        this.payment = payment;
        this.orderId = order.getId();
        this.order = order;
        this.amount = amount;
        this.reason = reason;
        this.status = RefundStatus.REQUESTED;
    }

    /**
     * 환불 요청 생성.
     */
    public static Refund request(Payment payment, Order order, BigDecimal amount, String reason) {
        return new Refund(payment, order, amount, reason);
    }

    /**
     * 환불 승인 처리.
     */
    public void approve(String pgRefundKey, OffsetDateTime approvedAt) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new IllegalStateException(
                String.format("Cannot approve refund in status: %s", this.status));
        }
        this.status = RefundStatus.APPROVED;
        this.pgRefundKey = pgRefundKey;
        this.approvedAt = approvedAt != null ? approvedAt : OffsetDateTime.now();
    }

    /**
     * 환불 거절 처리.
     */
    public void reject(String failedReason) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new IllegalStateException(
                String.format("Cannot reject refund in status: %s", this.status));
        }
        this.status = RefundStatus.FAILED;
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
    public Payment getPayment() { return payment; }
    public Order getOrder() { return order; }
    public Long getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public String getReason() { return reason; }
    public RefundStatus getStatus() { return status; }
    public String getPgRefundKey() { return pgRefundKey; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public String getFailedReason() { return failedReason; }

    /**
     * 환불 상태 (PRD §7.7).
     */
    public enum RefundStatus {
        REQUESTED,  // 환불 요청
        APPROVED,   // 환불 승인
        FAILED      // 환불 실패/거절
    }
}
