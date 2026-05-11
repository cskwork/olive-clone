package com.olive.commerce.payment.client.dto;

import java.time.LocalDateTime;

/**
 * PG 결제 승인 응답.
 *
 * @param status        승인 상태 (APPROVED, FAILED)
 * @param approvedAt    승인 시각 (FAILED 시 null)
 * @param failedReason  실패 사유 (APPROVED 시 null)
 */
public record ConfirmResponse(
    String status,
    LocalDateTime approvedAt,
    String failedReason
) {}
