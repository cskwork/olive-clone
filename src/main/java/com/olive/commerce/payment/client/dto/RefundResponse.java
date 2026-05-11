package com.olive.commerce.payment.client.dto;

import java.time.LocalDateTime;

/**
 * PG 환불 응답.
 */
public record RefundResponse(
    String status,
    LocalDateTime refundedAt,
    String pgRefundKey
) {}
