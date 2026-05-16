package com.olive.commerce.payment;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.payment.RefundDtos.RefundRequestDto;
import com.olive.commerce.payment.RefundDtos.RefundResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 환불 컨트롤러 (사용자).
 * <p>
 * POST /api/me/orders/{orderNo}/refund-request
 */
@RestController
@RequestMapping("/api/me/orders")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    /**
     * 환불 요청.
     *
     * @param orderNo 주문 번호
     * @param request 환불 요청
     * @param principal 인증된 회원
     * @return 환불 응답
     */
    @PostMapping("/{orderNo}/refund-request")
    public ApiResponse<RefundResponse> requestRefund(
            @PathVariable String orderNo,
            @Valid @RequestBody RefundRequestDto request,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Long memberId = principal.memberId();
        Refund refund = refundService.requestRefund(memberId, orderNo, request);
        return ApiResponse.success(RefundResponse.from(refund));
    }
}
