package com.olive.commerce.payment;

import com.olive.commerce.payment.Refund.RefundStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 환불 관련 DTO.
 */
public class RefundDtos {

    /**
     * 환불 요청 (사용자).
     * <p>
     * items가 비어 있으면 전체 환불, 채워져 있으면 부분 환불로 처리됩니다.
     */
    public record RefundRequestDto(
            @Size(max = 500, message = "환불 사유는 500자 이내여야 합니다")
            String reason,
            @NotEmpty(message = "환불 항목은 1개 이상이어야 합니다")
            @Valid
            List<RefundItem> items
    ) {
        public record RefundItem(
                @NotNull(message = "orderItemId는 필수입니다")
                Long orderItemId,
                @Min(value = 1, message = "환불 수량은 1 이상이어야 합니다")
                int quantity
        ) {}
    }

    /**
     * 환불 응답 (사용자).
     */
    public record RefundResponse(
            Long refundId,
            String orderNo,
            BigDecimal amount,
            RefundStatus status,
            String reason,
            OffsetDateTime requestedAt
    ) {
        public static RefundResponse from(Refund refund) {
            return new RefundResponse(
                    refund.getId(),
                    refund.getOrder().getOrderNo(),
                    refund.getAmount(),
                    refund.getStatus(),
                    refund.getReason(),
                    refund.getRequestedAt()
            );
        }
    }

    /**
     * 환불 거절 요청 (관리자).
     */
    public record RejectRequest(
            String reason
    ) {}

    /**
     * 환불 승인 응답 (관리자).
     */
    public record ApproveResponse(
            Long refundId,
            RefundStatus status,
            String pgRefundKey,
            String message
    ) {}

    /**
     * 환불 목록 응답 (관리자).
     */
    public record AdminResponse(
            Long refundId,
            String orderNo,
            Long orderId,
            Long paymentId,
            BigDecimal amount,
            String reason,
            RefundStatus status,
            String pgRefundKey,
            OffsetDateTime requestedAt,
            OffsetDateTime approvedAt,
            String failedReason
    ) {
        public static AdminResponse from(Refund refund) {
            return new AdminResponse(
                    refund.getId(),
                    refund.getOrder() != null ? refund.getOrder().getOrderNo() : null,
                    refund.getOrderId(),
                    refund.getPayment().getId(),
                    refund.getAmount(),
                    refund.getReason(),
                    refund.getStatus(),
                    refund.getPgRefundKey(),
                    refund.getRequestedAt(),
                    refund.getApprovedAt(),
                    refund.getFailedReason()
            );
        }
    }

    /**
     * 환불 목록 필터 (관리자).
     */
    public record RefundListFilter(
            RefundStatus status,
            Integer page,
            Integer size
    ) {}
}
