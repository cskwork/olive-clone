package com.olive.commerce.promotion;

import com.olive.commerce.promotion.Coupon.DiscountType;
import com.olive.commerce.promotion.Coupon.CouponStatus;
import com.olive.commerce.promotion.MemberCoupon.MemberCouponStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 쿠폰 관련 DTO들.
 */
public sealed interface CouponDtos {

    // ========== Admin API DTOs ==========

    /**
     * 쿠폰 생성 요청.
     */
    record AdminCreateRequest(
            @NotBlank(message = "쿠폰명은 필수입니다")
            @Size(max = 255, message = "쿠폰명은 255자 이하여야 합니다")
            String name,

            @NotNull(message = "할인 타입은 필수입니다")
            DiscountType discountType,

            @NotNull(message = "할인 값은 필수입니다")
            @DecimalMin(value = "0", message = "할인 값은 0 이상이어야 합니다")
            BigDecimal discountValue,

            @DecimalMin(value = "0", message = "최소 주문 금액은 0 이상이어야 합니다")
            BigDecimal minOrderAmount,

            @NotNull(message = "시작일은 필수입니다")
            OffsetDateTime startedAt,

            @NotNull(message = "종료일은 필수입니다")
            OffsetDateTime endedAt,

            @Positive(message = "최대 발급 수량은 양수여야 합니다")
            Integer maxIssueCount
    ) implements CouponDtos {}

    /**
     * 쿠폰 상태 변경 요청.
     */
    record StatusUpdateRequest(
            @NotNull(message = "상태는 필수입니다")
            CouponStatus status
    ) implements CouponDtos {}

    /**
     * 대량 발급 요청.
     */
    record BulkIssueRequest(
            @NotEmpty(message = "회원 ID 목록은 비어있을 수 없습니다")
            List<@NotNull Long> memberIds
    ) implements CouponDtos {}

    /**
     * 대량 발급 응답.
     */
    record BulkIssueResponse(
            int successCount,
            int failedCount,
            List<@NotNull Long> failedMemberIds
    ) implements CouponDtos {}

    /**
     * 쿠폰 목록 필터.
     */
    record CouponListFilter(
            CouponStatus status,
            DiscountType discountType
    ) implements CouponDtos {}

    /**
     * 쿠폰 응답 (Admin).
     */
    record AdminResponse(
            Long id,
            String name,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal minOrderAmount,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            CouponStatus status,
            Integer maxIssueCount,
            Integer issuedCount,
            String createdAt
    ) implements CouponDtos {
        static AdminResponse from(Coupon coupon) {
            return new AdminResponse(
                    coupon.getId(),
                    coupon.getName(),
                    coupon.getDiscountType(),
                    coupon.getDiscountValue(),
                    coupon.getMinOrderAmount(),
                    coupon.getStartedAt(),
                    coupon.getEndedAt(),
                    coupon.getStatus(),
                    coupon.getMaxIssueCount(),
                    coupon.getIssuedCount(),
                    coupon.getCreatedAt() != null ? coupon.getCreatedAt().toString() : null
            );
        }
    }

    // ========== User API DTOs ==========

    /**
     * 회원 쿠폰 응답.
     */
    record MemberCouponResponse(
            Long id,
            Long couponId,
            String couponName,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal minOrderAmount,
            OffsetDateTime expiresAt,
            MemberCouponStatus status,
            String issuedAt
    ) implements CouponDtos {
        static MemberCouponResponse from(MemberCoupon mc) {
            Coupon coupon = mc.getCoupon();
            return new MemberCouponResponse(
                    mc.getId(),
                    mc.getCouponId(),
                    coupon != null ? coupon.getName() : null,
                    coupon != null ? coupon.getDiscountType() : null,
                    coupon != null ? coupon.getDiscountValue() : null,
                    coupon != null ? coupon.getMinOrderAmount() : null,
                    mc.getExpiresAt(),
                    mc.getStatus(),
                    mc.getIssuedAt() != null ? mc.getIssuedAt().toString() : null
            );
        }
    }

    // ========== Service DTOs ==========

    /**
     * 검증된 쿠폰 정보 (OLV-061 주문 생성 시 사용).
     */
    record ValidatedCoupon(
            Long memberCouponId,
            Long couponId,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal discountAmount  // 계산된 할인 금액
    ) implements CouponDtos {}

    /**
     * 쿠폰 사용 불가 사유.
     */
    enum CouponInvalidReason {
        EXPIRED,                  // 유효 기간 만료
        NOT_OWNED,                // 회원이 소유하지 않은 쿠폰
        ALREADY_USED,             // 이미 사용된 쿠폰
        MIN_AMOUNT_NOT_MET,       // 최소 주문 금액 미달
        NOT_APPLICABLE_PRODUCT,   // 적용 불가 상품 (향후 확장)
        COUPON_INACTIVE,          // 비활성화된 쿠폰
        COUPON_NOT_FOUND          // 존재하지 않는 쿠폰
    }

    /**
     * 쿠폰 예약 결과 (OLV-061에서 호출).
     */
    record TryReserveResult(
            boolean success,
            ValidatedCoupon validatedCoupon,
            CouponInvalidReason failureReason
    ) implements CouponDtos {
        static TryReserveResult success(ValidatedCoupon coupon) {
            return new TryReserveResult(true, coupon, null);
        }

        static TryReserveResult failure(CouponInvalidReason reason) {
            return new TryReserveResult(false, null, reason);
        }
    }
}
