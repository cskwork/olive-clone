package com.olive.commerce.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * PG 트랜잭션 기록 엔티티 (PRD §20.4, V9__payment.sql).
 * <p>
 * 멱등성 보호와 사후 분석(post-mortem)을 위한 전체 PG 응답 저장.
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "kind", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private TransactionKind kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pg_response_json", columnDefinition = "jsonb")
    private String pgResponseJson;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "idempotency_key")
    private UUID idempotencyKey;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected PaymentTransaction() {}

    private PaymentTransaction(Payment payment, TransactionKind kind, String pgResponseJson,
                               Integer httpStatus, UUID idempotencyKey) {
        this.payment = payment;
        this.kind = kind;
        this.pgResponseJson = pgResponseJson;
        this.httpStatus = httpStatus;
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * 클라이언트 요청 트랜잭션 생성 (idempotency key 있음).
     */
    public static PaymentTransaction clientRequest(Payment payment, TransactionKind kind,
                                                     String pgResponseJson, Integer httpStatus,
                                                     UUID idempotencyKey) {
        return new PaymentTransaction(payment, kind, pgResponseJson, httpStatus, idempotencyKey);
    }

    /**
     * PG 웹훅 트랜잭션 생성 (idempotency key 없음).
     */
    public static PaymentTransaction webhook(Payment payment, String pgResponseJson, Integer httpStatus) {
        return new PaymentTransaction(payment, TransactionKind.WEBHOOK, pgResponseJson, httpStatus, null);
    }

    // Getters
    public Long getId() { return id; }
    public Payment getPayment() { return payment; }
    public TransactionKind getKind() { return kind; }
    public String getPgResponseJson() { return pgResponseJson; }
    public Integer getHttpStatus() { return httpStatus; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /**
     * 트랜잭션 종류.
     */
    public enum TransactionKind {
        REQUEST,   // 결제 요청
        APPROVE,   // 결제 승인
        CANCEL,    // 결제 취소
        WEBHOOK,   // PG 웹훅
        REFUND     // 환불
    }
}
