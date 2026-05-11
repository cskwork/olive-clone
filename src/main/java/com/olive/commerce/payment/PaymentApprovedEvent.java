package com.olive.commerce.payment;

import org.springframework.context.ApplicationEvent;
import org.springframework.transaction.event.TransactionPhase;

/**
 * 결제 승인 이벤트 (PRD §8.4 Step 8).
 * <p>
 * AFTER_COMMIT 페이즈에 발행되어 outbox 이벤트를 통해 외부 시스템으로 fan-out.
 */
public class PaymentApprovedEvent extends ApplicationEvent {

    private final Long orderId;
    private final String orderNo;
    private final Long paymentId;
    private final String paymentKey;
    private final java.math.BigDecimal approvedAmount;

    public PaymentApprovedEvent(Object source, Long orderId, String orderNo,
                                 Long paymentId, String paymentKey,
                                 java.math.BigDecimal approvedAmount) {
        super(source);
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.paymentId = paymentId;
        this.paymentKey = paymentKey;
        this.approvedAmount = approvedAmount;
    }

    public Long orderId() { return orderId; }
    public String orderNo() { return orderNo; }
    public Long paymentId() { return paymentId; }
    public String paymentKey() { return paymentKey; }
    public java.math.BigDecimal approvedAmount() { return approvedAmount; }

    /**
     * AFTER_COMMIT 페이즈 리스너에서 사용하기 위한 팩토리 메서드.
     */
    public static TransactionPhase phase() {
        return TransactionPhase.AFTER_COMMIT;
    }
}
