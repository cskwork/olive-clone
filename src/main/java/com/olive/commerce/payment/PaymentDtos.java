package com.olive.commerce.payment;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

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
}
