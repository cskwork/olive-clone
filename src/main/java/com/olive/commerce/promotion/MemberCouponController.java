package com.olive.commerce.promotion;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.promotion.CouponDtos.MemberCouponResponse;
import com.olive.commerce.promotion.MemberCoupon.MemberCouponStatus;
import com.olive.commerce.promotion.CouponService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
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
     * @param currentUser 인증된 회원
     * @return 회원 쿠폰 목록
     */
    @GetMapping
    public ApiResponse<List<MemberCouponResponse>> list(
            @RequestParam(required = false) MemberCouponStatus status,
            @AuthenticationPrincipal User currentUser
    ) {
        // TODO: SecurityContext에서 memberId 추출
        // 임시: 현재는 username에서 ID 파싱 (실제로는 JWT에서 memberId 추출)
        Long memberId = extractMemberId(currentUser);
        List<MemberCouponResponse> coupons = couponService.listMemberCoupons(memberId, status);
        return ApiResponse.success(coupons);
    }

    /**
     * User 객체에서 memberId를 추출합니다.
     * <p>TODO: 실제 구현에서는 JWT claim에서 memberId를 추출해야 합니다.
     *
     * @param user Spring Security User 객체
     * @return 회원 ID
     */
    private Long extractMemberId(User user) {
        // 임시 구현: username에 ID가 있다고 가정
        // 실제로는 JwtTokenProvider에서 memberId를 claim에 포함해야 함
        try {
            return Long.parseLong(user.getUsername());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid member ID in authentication token");
        }
    }
}
