package com.olive.commerce.payment;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.payment.Refund.RefundStatus;
import com.olive.commerce.payment.RefundDtos.AdminResponse;
import com.olive.commerce.payment.RefundDtos.ApproveResponse;
import com.olive.commerce.payment.RefundDtos.RejectRequest;
import com.olive.commerce.payment.RefundDtos.RefundListFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 환불 관리 컨트롤러.
 * <p>
 * GET /api/admin/refunds - 목록 조회
 * POST /api/admin/refunds/{refundId}/approve - 승인
 * POST /api/admin/refunds/{refundId}/reject - 거절
 * GET /api/admin/refunds/{refundId} - 상세 조회
 */
@RestController
@RequestMapping("/api/admin/refunds")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ORDER_ADMIN')")
public class RefundAdminController {

    private final RefundService refundService;

    /**
     * 환불 목록 조회.
     *
     * @param status 환불 상태 (선택)
     * @param page   페이지 번호 (기본 0)
     * @param size   페이지 크기 (기본 20)
     * @return 환불 목록
     */
    @GetMapping
    public ApiResponse<Page<AdminResponse>> listRefunds(
            @RequestParam(required = false) RefundStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("requestedAt").descending());
        Page<AdminResponse> result = refundService.listRefunds(status, pageable);
        return ApiResponse.success(result);
    }

    /**
     * 환불 상세 조회.
     *
     * @param refundId 환불 ID
     * @return 환불 상세
     */
    @GetMapping("/{refundId}")
    public ApiResponse<AdminResponse> getRefundDetail(@PathVariable Long refundId) {
        AdminResponse result = refundService.getRefundDetail(refundId);
        return ApiResponse.success(result);
    }

    /**
     * 환불 승인.
     *
     * @param refundId 환불 ID
     * @param principal 인증된 관리자
     * @return 승인 응답
     */
    @PostMapping("/{refundId}/approve")
    public ApiResponse<ApproveResponse> approveRefund(
            @PathVariable Long refundId,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Long adminId = principal.memberId();
        ApproveResponse result = refundService.approveRefund(refundId, adminId);
        return ApiResponse.success(result);
    }

    /**
     * 환불 거절.
     *
     * @param refundId 환불 ID
     * @param request  거절 요청
     * @param principal 인증된 관리자
     * @return 거절된 환불
     */
    @PostMapping("/{refundId}/reject")
    public ApiResponse<AdminResponse> rejectRefund(
            @PathVariable Long refundId,
            @Valid @RequestBody RejectRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Long adminId = principal.memberId();
        Refund refund = refundService.rejectRefund(refundId, request, adminId);
        return ApiResponse.success(AdminResponse.from(refund));
    }
}
