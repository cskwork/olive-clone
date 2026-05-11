package com.olive.commerce.promotion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 포인트 원장 엔티티 (PRD §7.8).
 * <p>모든 포인트 변동을 기록하는 append-only 테이블입니다.
 * source of truth이며, {@code points.balance}는 트리거로 동기화되는 캐시입니다.
 */
@Entity
@Table(name = "point_histories")
public class PointHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "change_type", nullable = false, length = 20)
    private String changeType;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "available_at", nullable = false)
    private OffsetDateTime availableAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected PointHistory() {}

    private PointHistory(
            Long memberId,
            ChangeType changeType,
            BigDecimal amount,
            String reason,
            Long orderId,
            OffsetDateTime availableAt,
            OffsetDateTime expiresAt
    ) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId must not be null");
        }
        if (changeType == null) {
            throw new IllegalArgumentException("changeType must not be null");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (availableAt == null) {
            throw new IllegalArgumentException("availableAt must not be null");
        }
        if (expiresAt != null && expiresAt.isBefore(availableAt)) {
            throw new IllegalArgumentException("expiresAt must be after availableAt");
        }

        this.memberId = memberId;
        this.changeType = changeType.name();
        this.amount = amount;
        this.reason = reason;
        this.orderId = orderId;
        this.availableAt = availableAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 적립 내역을 생성합니다.
     */
    public static PointHistory earn(
            Long memberId,
            BigDecimal amount,
            String reason,
            Long orderId,
            OffsetDateTime availableAt,
            OffsetDateTime expiresAt
    ) {
        return new PointHistory(memberId, ChangeType.EARN, amount, reason, orderId, availableAt, expiresAt);
    }

    /**
     * 사용 내역을 생성합니다.
     */
    public static PointHistory use(
            Long memberId,
            BigDecimal amount,
            String reason,
            Long orderId
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new PointHistory(memberId, ChangeType.USE, amount, reason, orderId, now, null);
    }

    /**
     * 사용 내역을 생성합니다 (지정된 사용 가능 시점).
     */
    public static PointHistory use(
            Long memberId,
            BigDecimal amount,
            String reason,
            Long orderId,
            OffsetDateTime availableAt
    ) {
        return new PointHistory(memberId, ChangeType.USE, amount, reason, orderId, availableAt, null);
    }

    /**
     * 취소 내역을 생성합니다 (적립/사용 복구).
     */
    public static PointHistory cancel(
            Long memberId,
            BigDecimal amount,
            String reason,
            Long orderId
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        return new PointHistory(memberId, ChangeType.CANCEL, amount, reason, orderId, now, null);
    }

    /**
     * 소멸 내역을 생성합니다.
     */
    public static PointHistory expire(
            Long memberId,
            BigDecimal amount,
            String reason,
            Long originalHistoryId
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        // expire는 orderId 대신 원본 history ID를 reason에 포함
        return new PointHistory(memberId, ChangeType.EXPIRE, amount, reason, null, now, null);
    }

    /**
     * 관리자 조정 내역을 생성합니다.
     */
    public static PointHistory adminAdjust(
            Long memberId,
            BigDecimal amount,
            String reason
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        return new PointHistory(memberId, ChangeType.ADMIN_ADJUST, amount, reason, null, now, null);
    }

    // Getters
    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public ChangeType getChangeType() { return ChangeType.valueOf(changeType); }
    public BigDecimal getAmount() { return amount; }
    public String getReason() { return reason; }
    public Long getOrderId() { return orderId; }
    public OffsetDateTime getAvailableAt() { return availableAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /**
     * 포인트 변화 타입 (PRD §7.8).
     */
    public enum ChangeType {
        EARN,         // 적립
        USE,          // 사용
        CANCEL,       // 취소 (적립/사용 복구)
        EXPIRE,       // 소멸
        ADMIN_ADJUST  // 관리자 조정
    }
}
