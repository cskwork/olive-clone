package com.olive.commerce.payment;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;

/**
 * 주문 환불 완료 이벤트 (OLV-074).
 * <p>
 * 환불 승인 후 발행되어 외부 시스템에 환불 사실을 알립니다.
 */
public class OrderRefundedEvent extends ApplicationEvent {

    private final Long refundId;
    private final Long orderId;
    private final String orderNo;
    private final Long paymentId;
    private final BigDecimal amount;

    public OrderRefundedEvent(
            Object source,
            Long refundId,
            Long orderId,
            String orderNo,
            Long paymentId,
            BigDecimal amount
    ) {
        super(source);
        this.refundId = refundId;
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.paymentId = paymentId;
        this.amount = amount;
    }

    public Long refundId() { return refundId; }
    public Long orderId() { return orderId; }
    public String orderNo() { return orderNo; }
    public Long paymentId() { return paymentId; }
    public BigDecimal amount() { return amount; }
}
