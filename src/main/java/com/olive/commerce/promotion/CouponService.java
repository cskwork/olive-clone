package com.olive.commerce.promotion;

import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.promotion.Coupon.CouponStatus;
import com.olive.commerce.promotion.CouponDtos.AdminCreateRequest;
import com.olive.commerce.promotion.CouponDtos.AdminResponse;
import com.olive.commerce.promotion.CouponDtos.BulkIssueRequest;
import com.olive.commerce.promotion.CouponDtos.BulkIssueResponse;
import com.olive.commerce.promotion.CouponDtos.CouponInvalidReason;
import com.olive.commerce.promotion.CouponDtos.MemberCouponResponse;
import com.olive.commerce.promotion.CouponDtos.TryReserveResult;
import com.olive.commerce.promotion.CouponDtos.ValidatedCoupon;
import com.olive.commerce.promotion.MemberCoupon.MemberCouponStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 쿠폰 서비스.
 * <p>쿠폰 생성, 대량 발급, 검증, 사용, 복구를 제공합니다.
 * <p>OLV-061(주문 생성)에서 호출하는 {@code validate()}, {@code tryReserve()},
 * {@code markUsed()}, {@code restore()} 메서드를 포함합니다.
 */
@Service
public class CouponService {

    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final AuditLogger auditLogger;

    public CouponService(
            CouponRepository couponRepository,
            MemberCouponRepository memberCouponRepository,
            AuditLogger auditLogger
    ) {
        this.couponRepository = couponRepository;
        this.memberCouponRepository = memberCouponRepository;
        this.auditLogger = auditLogger;
    }

    // ========== Admin API ==========

    /**
     * 쿠폰을 생성합니다.
     *
     * @param request 생성 요청
     * @param adminId 관리자 ID
     * @return 생성된 쿠폰
     */
    @Transactional
    public AdminResponse createCoupon(AdminCreateRequest request, Long adminId) {
        Coupon coupon = Coupon.create(
                request.name(),
                request.discountType(),
                request.discountValue(),
                request.minOrderAmount(),
                request.startedAt(),
                request.endedAt(),
                request.maxIssueCount()
        );
        Coupon saved = couponRepository.save(coupon);

        auditLogger.log("ADMIN_MUTATION", Map.of(
                "adminId", adminId != null ? adminId : "UNKNOWN",
                "action", "CREATE_COUPON",
                "couponId", saved.getId(),
                "name", saved.getName(),
                "discountType", saved.getDiscountType().name()
        ));

        return AdminResponse.from(saved);
    }

    /**
     * 활성 쿠폰 목록을 조회합니다.
     *
     * @return 활성 쿠폰 목록
     */
    @Transactional(readOnly = true)
    public List<AdminResponse> listActiveCoupons() {
        return couponRepository.findByStatus(CouponStatus.ACTIVE.name()).stream()
                .map(AdminResponse::from)
                .toList();
    }

    /**
     * 쿠폰 상태를 변경합니다.
     *
     * @param couponId 쿠폰 ID
     * @param newStatus 새로운 상태
     * @param adminId   관리자 ID
     */
    @Transactional
    public void updateCouponStatus(Long couponId, CouponStatus newStatus, Long adminId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND, "couponId=" + couponId));

        coupon.updateStatus(newStatus);
        couponRepository.save(coupon);

        auditLogger.log("ADMIN_MUTATION", Map.of(
                "adminId", adminId != null ? adminId : "UNKNOWN",
                "action", "UPDATE_COUPON_STATUS",
                "couponId", couponId,
                "newStatus", newStatus.name()
        ));
    }

    /**
     * 회원들에게 쿠폰을 대량 발급합니다.
     * <p>동시성 제어를 위해 {@code SELECT FOR UPDATE}를 사용하여
     * {@code issued_count} 갱신의 원자성을 보장합니다.
     *
     * @param couponId 쿠폰 ID
     * @param request  발급 요청 (회원 ID 목록)
     * @param adminId  관리자 ID
     * @return 발급 결과 (성공/실패 카운트, 실패한 회원 ID 목록)
     */
    @Transactional
    public BulkIssueResponse bulkIssue(Long couponId, BulkIssueRequest request, Long adminId) {
        // FOR UPDATE로 잠금 획득
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND, "couponId=" + couponId));

        if (!coupon.isActive()) {
            throw new BusinessException(ErrorCode.COUPON_INVALID, "Coupon is not active: " + couponId);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!coupon.isValidPeriod(now)) {
            throw new BusinessException(ErrorCode.COUPON_EXPIRED, "Coupon period is not valid");
        }

        List<Long> memberIds = request.memberIds();
        List<Long> failedMemberIds = new ArrayList<>();
        int successCount = 0;

        for (Long memberId : memberIds) {
            // max_issue_count 체크
            if (!coupon.canIssueMore()) {
                // 더 이상 발급할 수 없음
                failedMemberIds.addAll(memberIds.subList(memberIds.indexOf(memberId), memberIds.size()));
                break;
            }

            // 이미 발급받은 회원인지 확인 (유니크 인덱스 위반 방지)
            if (memberCouponRepository.findIssuedByMemberIdAndCouponId(memberId, couponId).isPresent()) {
                failedMemberIds.add(memberId);
                continue;
            }

            // 회원 쿠폰 생성 (만료일 = 쿠폰 종료일)
            MemberCoupon memberCoupon = MemberCoupon.issue(memberId, couponId, coupon.getEndedAt());
            memberCouponRepository.save(memberCoupon);

            // 쿠피 issued_count 증가
            coupon.incrementIssuedCount();
            successCount++;
        }

        couponRepository.save(coupon);

        auditLogger.log("ADMIN_MUTATION", Map.of(
                "adminId", adminId != null ? adminId : "UNKNOWN",
                "action", "BULK_ISSUE_COUPON",
                "couponId", couponId,
                "requestedCount", memberIds.size(),
                "successCount", successCount,
                "failedCount", failedMemberIds.size()
        ));

        return new BulkIssueResponse(successCount, failedMemberIds.size(), failedMemberIds);
    }

    // ========== User API ==========

    /**
     * 회원의 쿠폰 목록을 조회합니다.
     *
     * @param memberId 회원 ID
     * @param status   쿠폰 상태 (null이면 전체)
     * @return 회원 쿠폰 목록
     */
    @Transactional(readOnly = true)
    public List<MemberCouponResponse> listMemberCoupons(Long memberId, MemberCouponStatus status) {
        List<MemberCoupon> coupons;
        if (status != null) {
            coupons = memberCouponRepository.findByMemberIdAndStatus(memberId, status.name());
        } else {
            coupons = memberCouponRepository.findByMemberId(memberId);
        }

        // Coupon 정보를 함께 로드하기 위해 fetch join 사용 또는 별도 조회
        // 간단한 구현을 위해 각각 쿠폰 정보를 로드
        return coupons.stream()
                .map(mc -> {
                    // 쿠폰 정보 로드 (영속성 컨텍스트 or 별도 조회)
                    if (mc.getCoupon() == null) {
                        couponRepository.findById(mc.getCouponId()).ifPresent(mc::setCoupon);
                    }
                    return MemberCouponResponse.from(mc);
                })
                .toList();
    }

    // ========== Service Methods for OLV-061 (Order) ==========

    /**
     * 회원 쿠폰을 검증합니다 (OLV-061 주문 생성 시 호출).
     *
     * @param memberId     회원 ID
     * @param memberCouponId 회원 쿠폰 ID
     * @param orderAmount  주문 금액
     * @return 검증된 쿠폰 정보
     * @throws BusinessException {@link ErrorCode#COUPON_INVALID}과 함께 실패 사유를 포함
     */
    @Transactional(readOnly = true)
    public ValidatedCoupon validate(Long memberId, Long memberCouponId, BigDecimal orderAmount) {
        MemberCoupon mc = memberCouponRepository.findByIdWithCoupon(memberCouponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND, "memberCouponId=" + memberCouponId));

        // 회원 소유 검증
        if (!mc.getMemberId().equals(memberId)) {
            throw new BusinessException(ErrorCode.COUPON_INVALID, CouponInvalidReason.NOT_OWNED.name());
        }

        // 상태 검증
        if (!mc.isIssued()) {
            if (mc.isUsed()) {
                throw new BusinessException(ErrorCode.COUPON_ALREADY_USED, CouponInvalidReason.ALREADY_USED.name());
            }
            throw new BusinessException(ErrorCode.COUPON_INVALID, "Coupon status is not ISSUED: " + mc.getStatus());
        }

        // 만료 검증
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (mc.isExpired(now)) {
            throw new BusinessException(ErrorCode.COUPON_EXPIRED, CouponInvalidReason.EXPIRED.name());
        }

        Coupon coupon = mc.getCoupon();
        if (coupon == null) {
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND, "Coupon not found: " + mc.getCouponId());
        }

        // 쿠폰 활성 상태 검증
        if (!coupon.isActive()) {
            throw new BusinessException(ErrorCode.COUPON_INVALID, CouponInvalidReason.COUPON_INACTIVE.name());
        }

        // 쿠폰 유효 기간 검증
        if (!coupon.isValidPeriod(now)) {
            throw new BusinessException(ErrorCode.COUPON_EXPIRED, CouponInvalidReason.EXPIRED.name());
        }

        // 최소 주문 금액 검증
        if (coupon.getMinOrderAmount() != null && orderAmount.compareTo(coupon.getMinOrderAmount()) < 0) {
            throw new BusinessException(ErrorCode.COUPON_INVALID, CouponInvalidReason.MIN_AMOUNT_NOT_MET.name());
        }

        // 할인 금액 계산
        BigDecimal discountAmount = calculateDiscountAmount(coupon, orderAmount);

        return new ValidatedCoupon(mc.getId(), coupon.getId(), coupon.getDiscountType(), coupon.getDiscountValue(), discountAmount);
    }

    /**
     * 주문 생성 시 쿠폰 사용을 예약합니다 (OLV-061에서 호출).
     *
     * @param memberId     회원 ID
     * @param memberCouponId 회원 쿠폰 ID
     * @param orderAmount  주문 금액
     * @return 예약 결과
     */
    @Transactional(readOnly = true)
    public TryReserveResult tryReserve(Long memberId, Long memberCouponId, BigDecimal orderAmount) {
        try {
            ValidatedCoupon validated = validate(memberId, memberCouponId, orderAmount);
            return TryReserveResult.success(validated);
        } catch (BusinessException e) {
            CouponInvalidReason reason = CouponInvalidReason.COUPON_NOT_FOUND; // default
            if (e.getMessage() != null) {
                try {
                    reason = CouponInvalidReason.valueOf(e.getMessage());
                } catch (IllegalArgumentException ignored) {
                    // message가 reason enum 값이 아닌 경우 기본값 사용
                }
            }
            return TryReserveResult.failure(reason);
        }
    }

    /**
     * 쿠폰을 사용 상태로 변경합니다 (주문 생성 트랜잭션 내에서 호출).
     *
     * @param memberCouponId 회원 쿠폰 ID
     * @param orderId        주문 ID
     */
    @Transactional
    public void markUsed(Long memberCouponId, Long orderId) {
        MemberCoupon mc = memberCouponRepository.findById(memberCouponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND, "memberCouponId=" + memberCouponId));

        mc.markUsed(orderId);
        memberCouponRepository.save(mc);
    }

    /**
     * 사용된 쿠폰을 복구합니다 (주문 취소 시 호출).
     *
     * @param memberCouponId 회원 쿠폰 ID
     * @param orderId        주문 ID
     */
    @Transactional
    public void restore(Long memberCouponId, Long orderId) {
        MemberCoupon mc = memberCouponRepository.findById(memberCouponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND, "memberCouponId=" + memberCouponId));

        // IDEMPOTENCY: 이미 복구된 쿠폰이면 무시
        if (mc.isIssued()) {
            return;
        }

        // orderId 검증 (optional: 실제 주문 ID와 일치하는지 확인)
        if (!orderId.equals(mc.getUsedOrderId())) {
            throw new BusinessException(ErrorCode.COUPON_INVALID, "Order ID mismatch: expected=" + mc.getUsedOrderId() + ", actual=" + orderId);
        }

        mc.restore();
        memberCouponRepository.save(mc);
    }

    // ========== Private Helper Methods ==========

    /**
     * 할인 금액을 계산합니다.
     *
     * @param coupon      쿠폰
     * @param orderAmount 주문 금액
     * @return 할인 금액
     */
    private BigDecimal calculateDiscountAmount(Coupon coupon, BigDecimal orderAmount) {
        return switch (coupon.getDiscountType()) {
            case FIXED_AMOUNT -> coupon.getDiscountValue();
            case PERCENTAGE -> {
                BigDecimal percent = coupon.getDiscountValue()
                        .divide(BigDecimal.valueOf(100), 4, BigDecimal.ROUND_HALF_UP);
                yield orderAmount.multiply(percent);
            }
            case FREE_SHIPPING -> BigDecimal.ZERO;  // 배송비는 별도 처리
            case BUY_ONE_GET_ONE -> BigDecimal.ZERO;  // BOGO는 별도 로직 필요
            case MEMBER_GRADE -> BigDecimal.ZERO;  // 등급 할인은 별도 로직 필요
        };
    }
}
