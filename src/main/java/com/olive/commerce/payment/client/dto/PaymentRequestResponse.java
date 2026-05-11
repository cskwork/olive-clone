package com.olive.commerce.payment.client.dto;

/**
 * PG 결제 요청 응답 (결제 준비 결과).
 */
public record PaymentRequestResponse(
    String paymentKey,
    String checkoutUrl
) {}
