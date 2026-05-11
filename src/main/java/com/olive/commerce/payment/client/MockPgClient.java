package com.olive.commerce.payment.client;

import com.olive.commerce.payment.client.dto.*;
import com.olive.commerce.payment.client.exception.PgTimeoutException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Mock PG 클라이언트 구현.
 * QA에서 결제 성공/실패/타임아웃 케이스를 재시작 없이 테스트할 수 있다.
 * 동작 모드는 {@link #setBehaviour(String)}으로 제어한다.
 */
public class MockPgClient implements PgClient {

    /**
     * 동작 모드.
     * - null 또는 "approve": APPROVED 반환 (기본)
     * - "fail": FAILED 반환 (failedReason=MOCK_FAIL)
     * - "timeout": 6초 대기 후 PgTimeoutException 발생
     */
    private volatile String behaviour = null;

    public void setBehaviour(String behaviour) {
        this.behaviour = behaviour;
    }

    @Override
    public PaymentRequestResponse requestPayment(PaymentRequest request) {
        String paymentKey = "mock-payment-key-" + UUID.randomUUID();
        String checkoutUrl = "https://mock.pg/checkout/" + paymentKey;
        return new PaymentRequestResponse(paymentKey, checkoutUrl);
    }

    @Override
    public ConfirmResponse confirmPayment(ConfirmRequest request) {
        if ("timeout".equals(behaviour)) {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PgTimeoutException("PG request interrupted", e);
            }
            throw new PgTimeoutException("PG request timed out after 6s");
        }

        if ("fail".equals(behaviour)) {
            return new ConfirmResponse("FAILED", null, "MOCK_FAIL");
        }

        // 기본 동작 또는 "approve" 모드
        return new ConfirmResponse("APPROVED", LocalDateTime.now(), null);
    }

    @Override
    public CancelResponse cancelPayment(CancelRequest request) {
        return new CancelResponse("CANCELED", LocalDateTime.now(), request.cancelReason());
    }

    @Override
    public RefundResponse refund(RefundRequest request) {
        String pgRefundKey = "mock-refund-key-" + UUID.randomUUID();
        return new RefundResponse("REFUNDED", LocalDateTime.now(), pgRefundKey);
    }

    @Override
    public VerifyResponse verify(String paymentKey) {
        return new VerifyResponse("APPROVED", paymentKey, BigDecimal.ZERO);
    }
}
