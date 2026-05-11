package com.olive.commerce.promotion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 회원별 발급 쿠폰 엔티티 (PRD §7.8).
 * <p>회원이 발급받은 쿠폰의 상태(ISSUED, USED, EXPIRED, REVOKED)와 사용 정보를 관리합니다.
 */
@Entity
@Table(name = "member_coupons")
public class MemberCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne
    @JoinColumn(name = "coupon_id", nullable = false, insertable = false, updatable = false)
    private Coupon coupon;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "status", nullable = false, length = 20)
    private String status = MemberCouponStatus.ISSUED.name();

    @Column(name = "issued_at", nullable = false, insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime issuedAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "used_order_id")
    private Long usedOrderId;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected MemberCoupon() {}

    private MemberCoupon(Long memberId, Long couponId, OffsetDateTime expiresAt) {
        this.memberId = memberId;
        this.couponId = couponId;
        this.expiresAt = expiresAt;
        this.status = MemberCouponStatus.ISSUED.name();
        this.usedAt = null;
        this.usedOrderId = null;
    }

    public static MemberCoupon issue(Long memberId, Long couponId, OffsetDateTime expiresAt) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId must not be null");
        }
        if (couponId == null) {
            throw new IllegalArgumentException("couponId must not be null");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
        return new MemberCoupon(memberId, couponId, expiresAt);
    }

    public void markUsed(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (!isIssued()) {
            throw new IllegalStateException("Cannot mark coupon as used: status is not ISSUED");
        }
        this.status = MemberCouponStatus.USED.name();
        this.usedAt = OffsetDateTime.now();
        this.usedOrderId = orderId;
    }

    public void restore() {
        if (!isUsed()) {
            throw new IllegalStateException("Cannot restore coupon: status is not USED");
        }
        this.status = MemberCouponStatus.ISSUED.name();
        this.usedAt = null;
        this.usedOrderId = null;
    }

    public void revoke() {
        if (!isIssued()) {
            throw new IllegalStateException("Cannot revoke coupon: status is not ISSUED");
        }
        this.status = MemberCouponStatus.REVOKED.name();
    }

    public void markExpired() {
        if (!isIssued()) {
            throw new IllegalStateException("Cannot mark coupon as expired: status is not ISSUED");
        }
        this.status = MemberCouponStatus.EXPIRED.name();
    }

    public boolean isIssued() {
        return MemberCouponStatus.ISSUED.name().equals(status);
    }

    public boolean isUsed() {
        return MemberCouponStatus.USED.name().equals(status);
    }

    public boolean isExpired(OffsetDateTime now) {
        return now.isAfter(expiresAt);
    }

    // JPA relation setter
    void setCoupon(Coupon coupon) {
        this.coupon = coupon;
    }

    // Getters
    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public Coupon getCoupon() { return coupon; }
    public Long getCouponId() { return couponId; }
    public MemberCouponStatus getStatus() { return MemberCouponStatus.valueOf(status); }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public OffsetDateTime getUsedAt() { return usedAt; }
    public Long getUsedOrderId() { return usedOrderId; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }

    /**
     * 회원 쿠폰 상태.
     */
    public enum MemberCouponStatus {
        ISSUED,      // 발급됨 (사용 가능)
        USED,        // 사용됨
        EXPIRED,     // 만료됨
        REVOKED      // 취소됨 (관리자 취소)
    }
}
