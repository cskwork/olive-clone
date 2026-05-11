package com.olive.commerce.admin;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.promotion.CouponDtos;
import com.olive.commerce.promotion.CouponService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 쿠폰 관리자 API (OLV-051).
 * <p>경로: {@code /api/admin/coupons}
 * <p>권한: {@code PRODUCT_ADMIN} 또는 {@code SUPER_ADMIN}
 */
@RestController
@RequestMapping("/api/admin/coupons")
public class CouponAdminController {

    private final CouponService couponService;

    public CouponAdminController(CouponService couponService) {
        this.couponService = couponService;
    }

    /**
     * 쿠폰을 생성합니다.
     *
     * @param request 생성 요청
     * @return 생성된 쿠폰
     */
    @PostMapping
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ResponseEntity<ApiResponse<CouponDtos.AdminResponse>> create(
            @Valid @RequestBody CouponDtos.AdminCreateRequest request
    ) {
        // TODO: adminId는 SecurityContext에서 추출 (현재는 null)
        CouponDtos.AdminResponse response = couponService.createCoupon(request, null);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 활성 쿠폰 목록을 조회합니다.
     *
     * @return 활성 쿠폰 목록
     */
    @GetMapping
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<List<CouponDtos.AdminResponse>> list() {
        List<CouponDtos.AdminResponse> coupons = couponService.listActiveCoupons();
        return ApiResponse.success(coupons);
    }

    /**
     * 쿠폰 상태를 변경합니다 (비활성화/재활성화).
     *
     * @param couponId 쿠폰 ID
     * @param request  상태 변경 요청
     * @return 성공 응답
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> updateStatus(
            @PathVariable("id") Long couponId,
            @Valid @RequestBody CouponDtos.StatusUpdateRequest request
    ) {
        // TODO: adminId는 SecurityContext에서 추출
        couponService.updateCouponStatus(couponId, request.status(), null);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 회원들에게 쿠폰을 대량 발급합니다.
     *
     * @param couponId 쿠폰 ID
     * @param request  대량 발급 요청
     * @return 발급 결과
     */
    @PostMapping("/{id}/issue")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ResponseEntity<ApiResponse<CouponDtos.BulkIssueResponse>> bulkIssue(
            @PathVariable("id") Long couponId,
            @Valid @RequestBody CouponDtos.BulkIssueRequest request
    ) {
        // TODO: adminId는 SecurityContext에서 추출
        CouponDtos.BulkIssueResponse response = couponService.bulkIssue(couponId, request, null);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
