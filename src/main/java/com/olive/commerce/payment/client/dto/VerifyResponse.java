package com.olive.commerce.payment.client.dto;

import java.math.BigDecimal;

/**
 * PG 결제 검증 응답 (재정배치용).
 */
public record VerifyResponse(
    String status,
    String paymentKey,
    BigDecimal approvedAmount
) {}
