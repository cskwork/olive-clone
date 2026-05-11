package com.olive.commerce.payment.client.dto;

import java.time.LocalDateTime;

/**
 * PG 결제 취소 응답.
 */
public record CancelResponse(
    String status,
    LocalDateTime canceledAt,
    String canceledReason
) {}
