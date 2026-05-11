package com.olive.commerce.payment.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * PG 결제 승인 요청.
 *
 * @param paymentKey    PG사에서 발급한 결제 키 (결제 요청 시 PG가 반환)
 * @param orderId       주문 ID
 * @param amount        결제 금액 (orders.final_payment_amount와 일치해야 함)
 * @param idempotencyKey 멱등성 키 (재시도 시 동일 키 사용)
 */
public record ConfirmRequest(
    String paymentKey,
    Long orderId,
    BigDecimal amount,
    UUID idempotencyKey
) {}
