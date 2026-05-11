package com.olive.commerce.promotion;

import com.olive.commerce.promotion.PointHistory.ChangeType;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 포인트 관련 DTO들.
 */
public sealed interface PointDtos {

    /**
     * 포인트 잔액 응답.
     */
    record BalanceResponse(
            BigDecimal spendableBalance,
            int pendingCount
    ) implements PointDtos {}

    /**
     * 대기 중인 포인트 항목.
     */
    record PendingPoint(
            Long id,
            BigDecimal amount,
            String reason,
            OffsetDateTime availableAt,
            OffsetDateTime expiresAt
    ) implements PointDtos {
        static PendingPoint from(PointHistory h) {
            return new PendingPoint(
                    h.getId(),
                    h.getAmount(),
                    h.getReason(),
                    h.getAvailableAt(),
                    h.getExpiresAt()
            );
        }
    }

    /**
     * 포인트 내역 응답.
     */
    record HistoryResponse(
            Long id,
            ChangeType changeType,
            BigDecimal amount,
            String reason,
            Long orderId,
            String availableAt,
            String expiresAt,
            String createdAt
    ) implements PointDtos {
        static HistoryResponse from(PointHistory h) {
            return new HistoryResponse(
                    h.getId(),
                    h.getChangeType(),
                    h.getAmount(),
                    h.getReason(),
                    h.getOrderId(),
                    h.getAvailableAt() != null ? h.getAvailableAt().toString() : null,
                    h.getExpiresAt() != null ? h.getExpiresAt().toString() : null,
                    h.getCreatedAt() != null ? h.getCreatedAt().toString() : null
            );
        }
    }

    /**
     * 포인트 사용 요청.
     */
    record UseRequest(
            @Positive(message = "사용 금액은 양수여야 합니다")
            BigDecimal amount,

            Long orderId
    ) implements PointDtos {}
}
