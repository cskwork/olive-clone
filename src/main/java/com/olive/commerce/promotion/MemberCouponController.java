package com.olive.commerce.promotion;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.promotion.CouponDtos.MemberCouponResponse;
import com.olive.commerce.promotion.MemberCoupon.MemberCouponStatus;
import com.olive.commerce.promotion.CouponService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 회원 쿠폰 API (OLV-051).
 * <p>경로: {@code /api/me/coupons}
 * <p>권한: 인증된 회원 ({@code USER} 이상)
 */
@RestController
@RequestMapping("/api/me/coupons")
public class MemberCouponController {

    private final CouponService couponService;

    public MemberCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /**
     * 회원의 쿠폰 목록을 조회합니다.
     *
     * @param status 쿠폰 상태 필터 (선택)
     * @param principal 인증된 회원
     * @return 회원 쿠폰 목록
     */
    @GetMapping
    public ApiResponse<List<MemberCouponResponse>> list(
            @RequestParam(required = false) MemberCouponStatus status,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        long memberId = principal.memberId();
        List<MemberCouponResponse> coupons = couponService.listMemberCoupons(memberId, status);
        return ApiResponse.success(coupons);
    }
}
