package com.olive.commerce.payment.client;

import com.olive.commerce.payment.client.dto.*;

/**
 * PG(Payment Gateway) 클라이언트 인터페이스.
 * 실제 PG 구현(Toss, KCP 등)과 Mock 구현을 추상화한다.
 */
public interface PgClient {

    /**
     * 결제 요청 (클라이언트 → PG).
     * PG에 결제를 준비하고 paymentKey를 반환한다.
     */
    PaymentRequestResponse requestPayment(PaymentRequest request);

    /**
     * 결제 승인 (클라이언트 → PG).
     * paymentKey로 결제를 최종 승인한다.
     */
    ConfirmResponse confirmPayment(ConfirmRequest request);

    /**
     * 결제 취소 (클라이언트/관리자 → PG).
     */
    CancelResponse cancelPayment(CancelRequest request);

    /**
     * 환불 (관리자 → PG).
     */
    RefundResponse refund(RefundRequest request);

    /**
     * 결제 검증 (재정배치 배치: OLV-120).
     * PG callback이 도착하지 않은 결제를 검증한다.
     */
    VerifyResponse verify(String paymentKey);
}
