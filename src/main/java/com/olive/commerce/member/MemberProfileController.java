package com.olive.commerce.member;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.order.OrderRepository;
import com.olive.commerce.promotion.MemberCoupon;
import com.olive.commerce.promotion.MemberCouponRepository;
import com.olive.commerce.promotion.PointService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.math.BigDecimal;

/**
 * 마이페이지 프로필 API. OLV-012.
 *
 * GET /api/me — 현재 회원의 프로필 조회
 * GET /api/me/summary — 마이페이지 요약 (포인트, 쿠폰, 주문 수, 등급)
 * PATCH /api/me — 프로필 수정 (이름, 전화번호)
 */
@RestController
@RequestMapping("/api/me")
public class MemberProfileController {

    private final MemberRepository members;
    private final MemberGradeRepository grades;
    private final PointService pointService;
    private final MemberCouponRepository memberCoupons;
    private final OrderRepository orders;

    public MemberProfileController(
            MemberRepository members,
            MemberGradeRepository grades,
            PointService pointService,
            MemberCouponRepository memberCoupons,
            OrderRepository orders) {
        this.members = members;
        this.grades = grades;
        this.pointService = pointService;
        this.memberCoupons = memberCoupons;
        this.orders = orders;
    }

    @GetMapping
    public ApiResponse<MemberDtos.ProfileResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        Member m = members.findById(principal.memberId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.MEMBER_NOT_FOUND, "memberId=" + principal.memberId()));

        MemberGrade g = grades.findById(m.getGradeId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.MEMBER_GRADE_NOT_FOUND, "gradeId=" + m.getGradeId()));

        return ApiResponse.success(new MemberDtos.ProfileResponse(
            m.getId(),
            m.getEmail(),
            m.getName(),
            m.getPhone(),
            g.getName(),
            principal.role().name()
        ));
    }

    /**
     * 마이페이지 요약 정보를 반환합니다.
     * 포인트 잔액, 사용 가능 쿠폰 수, 전체 주문 수, 등급명을 한 번에 조회합니다.
     */
    @GetMapping("/summary")
    public ApiResponse<MemberDtos.SummaryResponse> summary(@AuthenticationPrincipal AuthenticatedUser principal) {
        long memberId = principal.memberId();

        Member m = members.findById(memberId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.MEMBER_NOT_FOUND, "memberId=" + memberId));

        MemberGrade g = grades.findById(m.getGradeId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.MEMBER_GRADE_NOT_FOUND, "gradeId=" + m.getGradeId()));

        BigDecimal pointBalance = pointService.spendableBalance(memberId, null);
        long usableCouponCount = memberCoupons.countByMemberIdAndStatus(memberId, MemberCoupon.MemberCouponStatus.ISSUED.name());
        long totalOrderCount = orders.countByMemberId(memberId);

        return ApiResponse.success(new MemberDtos.SummaryResponse(
            pointBalance,
            usableCouponCount,
            totalOrderCount,
            g.getName()
        ));
    }

    @PatchMapping
    public ApiResponse<MemberDtos.ProfileResponse> updateProfile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody MemberDtos.UpdateProfileRequest req) {
        Member m = members.findById(principal.memberId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.MEMBER_NOT_FOUND, "memberId=" + principal.memberId()));

        m.updateProfile(req.name(), req.phone());
        members.save(m);

        MemberGrade g = grades.findById(m.getGradeId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.MEMBER_GRADE_NOT_FOUND, "gradeId=" + m.getGradeId()));

        return ApiResponse.success(new MemberDtos.ProfileResponse(
            m.getId(),
            m.getEmail(),
            m.getName(),
            m.getPhone(),
            g.getName(),
            principal.role().name()
        ));
    }
}
