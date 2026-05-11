package com.olive.commerce.payment.client.dto;

import java.math.BigDecimal;

/**
 * PG 환불 요청.
 */
public record RefundRequest(
    String paymentKey,
    BigDecimal amount,
    String refundReason
) {}
