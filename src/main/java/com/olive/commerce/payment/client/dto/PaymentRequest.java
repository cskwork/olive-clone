package com.olive.commerce.payment.client.dto;

import java.math.BigDecimal;

/**
 * PG 결제 요청 (결제 준비).
 */
public record PaymentRequest(
    String orderId,
    BigDecimal amount,
    String orderName,
    String customerName,
    String customerEmail,
    String customerMobilePhone
) {}
