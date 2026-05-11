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

/**
 * 쿠폰 마스터 엔티티 (PRD §7.8).
 * <p>할인 타입, 할인 값, 최소 주문 금액, 유효 기간, 최대 발급 수량을 관리합니다.
 */
@Entity
@Table(name = "coupons")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "discount_type", nullable = false, length = 30)
    private String discountType;

    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "min_order_amount", precision = 12, scale = 2)
    private BigDecimal minOrderAmount;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at", nullable = false)
    private OffsetDateTime endedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status = CouponStatus.ACTIVE.name();

    @Column(name = "max_issue_count")
    private Integer maxIssueCount;

    @Column(name = "issued_count", nullable = false)
    private Integer issuedCount = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected Coupon() {}

    private Coupon(
            String name,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal minOrderAmount,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            Integer maxIssueCount
    ) {
        this.name = name;
        this.discountType = discountType.name();
        this.discountValue = discountValue;
        this.minOrderAmount = minOrderAmount;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.maxIssueCount = maxIssueCount;
        this.status = CouponStatus.ACTIVE.name();
        this.issuedCount = 0;
    }

    public static Coupon create(
            String name,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal minOrderAmount,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            Integer maxIssueCount
    ) {
        if (startedAt.isAfter(endedAt)) {
            throw new IllegalArgumentException("startedAt must be before endedAt");
        }
        if (discountValue.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("discountValue must be non-negative");
        }
        if (minOrderAmount != null && minOrderAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("minOrderAmount must be non-negative");
        }
        if (maxIssueCount != null && maxIssueCount <= 0) {
            throw new IllegalArgumentException("maxIssueCount must be positive");
        }
        return new Coupon(name, discountType, discountValue, minOrderAmount, startedAt, endedAt, maxIssueCount);
    }

    public void updateStatus(CouponStatus newStatus) {
        if (newStatus == null) {
            throw new IllegalArgumentException("newStatus must not be null");
        }
        this.status = newStatus.name();
    }

    public boolean isActive() {
        return CouponStatus.ACTIVE.name().equals(status);
    }

    public boolean isValidPeriod(OffsetDateTime now) {
        return !now.isBefore(startedAt) && !now.isAfter(endedAt);
    }

    public boolean canIssueMore() {
        if (maxIssueCount == null) {
            return true;
        }
        return issuedCount < maxIssueCount;
    }

    public void incrementIssuedCount() {
        if (maxIssueCount != null && issuedCount >= maxIssueCount) {
            throw new IllegalStateException("Cannot issue more coupons: maxIssueCount reached");
        }
        this.issuedCount++;
    }

    // Getters
    public Long getId() { return id; }
    public String getName() { return name; }
    public DiscountType getDiscountType() { return DiscountType.valueOf(discountType); }
    public BigDecimal getDiscountValue() { return discountValue; }
    public BigDecimal getMinOrderAmount() { return minOrderAmount; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getEndedAt() { return endedAt; }
    public CouponStatus getStatus() { return CouponStatus.valueOf(status); }
    public Integer getMaxIssueCount() { return maxIssueCount; }
    public Integer getIssuedCount() { return issuedCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /**
     * 할인 타입 (PRD §6.8).
     */
    public enum DiscountType {
        FIXED_AMOUNT,      // 정액 할인
        PERCENTAGE,        // 정률 할인
        FREE_SHIPPING,     // 무료 배송
        BUY_ONE_GET_ONE,   // N+1 행사
        MEMBER_GRADE       // 회원 등급 할인
    }

    /**
     * 쿠폰 상태.
     */
    public enum CouponStatus {
        ACTIVE,
        INACTIVE
    }
}
