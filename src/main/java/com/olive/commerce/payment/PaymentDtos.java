package com.olive.commerce.payment;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.olive.commerce.payment.Payment.PaymentStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payment API DTOs.
 */
public class PaymentDtos {

    /**
     * 결제 확인 요청 (PRD §8.4).
     *
     * @param orderNo    주문 번호
     * @param paymentKey PG사에서 발급한 결제 키
     * @param amount     결제 금액 (orders.final_payment_amount와 일치해야 함)
     */
    public record ConfirmRequest(
            @NotBlank(message = "orderNo는 필수입니다")
            String orderNo,

            @NotBlank(message = "paymentKey는 필수입니다")
            String paymentKey,

            @DecimalMin(value = "0.01", message = "amount는 0보다 커야 합니다")
            BigDecimal amount
    ) {}

    /**
     * 결제 확인 응답.
     *
     * @param orderId   주문 ID
     * @param orderNo   주문 번호
     * @param status    주문 상태
     * @param paymentKey 결제 키
     */
    public record ConfirmResponse(
            Long orderId,
            String orderNo,
            String status,
            String paymentKey
    ) {}

    /**
     * 금액 불일치 에러 응답 (422).
     *
     * @param requestedAmount 요청 금액
     * @param orderAmount     주문 금액
     * @param paymentAmount   결제 요청 금액
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AmountMismatchResponse(
            BigDecimal requestedAmount,
            BigDecimal orderAmount,
            BigDecimal paymentAmount
    ) {}

    /**
     * PG 웹훅 요청 (OLV-073).
     * <p>
     * 실제 PG사마다 필드명이 다르지만 MockPgClient는 이 형식을 따른다.
     *
     * @param paymentKey     PG사 결제 키
     * @param status         결제 상태 (APPROVED, FAILED, CANCELED, REFUNDED)
     * @param approvedAt     승인 시각 (optional, PG 타임스탬프 — 검증용으로만 사용)
     * @param approvedAmount 승인 금액
     * @param failedReason   실패 사유 (status=FAILED/CANCELED일 때)
     */
    public record WebhookRequest(
            @NotBlank(message = "paymentKey는 필수입니다")
            String paymentKey,

            @NotBlank(message = "status는 필수입니다")
            String status,

            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime approvedAt,

            BigDecimal approvedAmount,

            String failedReason
    ) {}

    /**
     * PG 웹훅 응답.
     * <p>
     * 항상 200을 반환하며, 요청이 중복되어도 동일한 응답.
     *
     * @param processed 처리 여부
     * @param message   처리 결과 메시지
     */
    public record WebhookResponse(
            boolean processed,
            String message
    ) {}
}
