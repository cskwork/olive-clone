package com.olive.commerce.payment.client.dto;

import java.math.BigDecimal;

/**
 * PG 결제 취소 요청.
 */
public record CancelRequest(
    String paymentKey,
    BigDecimal amount,
    String cancelReason
) {}
