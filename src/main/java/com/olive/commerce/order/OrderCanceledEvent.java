package com.olive.commerce.order;

import org.springframework.context.ApplicationEvent;

/**
 * 주문 취소 이벤트 (OLV-062).
 * <p>
 * 주문 취소 완료 후 발행되어 재고/쿠폰/포인트 복구 등의 후속 처리를 트리거합니다.
 */
public class OrderCanceledEvent extends ApplicationEvent {

    private final Long orderId;
    private final String orderNo;
    private final Long memberId;
    private final String reason;
    private final Order.OrderStatus fromStatus;
    private final CancelKind cancelKind;

    public OrderCanceledEvent(Object source, Long orderId, String orderNo, Long memberId,
                              String reason, Order.OrderStatus fromStatus, CancelKind cancelKind) {
        super(source);
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.memberId = memberId;
        this.reason = reason;
        this.fromStatus = fromStatus;
        this.cancelKind = cancelKind;
    }

    public Long orderId() {
        return orderId;
    }

    public String orderNo() {
        return orderNo;
    }

    public Long memberId() {
        return memberId;
    }

    public String reason() {
        return reason;
    }

    public Order.OrderStatus fromStatus() {
        return fromStatus;
    }

    public CancelKind cancelKind() {
        return cancelKind;
    }

    /**
     * 취소 주체.
     */
    public enum CancelKind {
        USER,   // 회원 본인
        ADMIN   // 관리자
    }
}
