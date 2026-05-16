package com.olive.commerce.delivery;

import org.springframework.context.ApplicationEvent;

/**
 * 배송 완료 이벤트.
 * <p>
 * 배송 상태가 DELIVERED로 전이되면 발행되어 포인트 적립/리뷰 작성 가능 등의
 * 후속 처리를 트리거합니다 (OLV-110).
 */
public class DeliveryCompletedEvent extends ApplicationEvent {

    private final Long deliveryId;
    private final Long orderId;
    private final String orderNo;
    private final Long memberId;
    private final String invoiceNo;

    public DeliveryCompletedEvent(Object source, Long deliveryId, Long orderId,
                                   String orderNo, Long memberId, String invoiceNo) {
        super(source);
        this.deliveryId = deliveryId;
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.memberId = memberId;
        this.invoiceNo = invoiceNo;
    }

    public Long deliveryId() { return deliveryId; }
    public Long orderId() { return orderId; }
    public String orderNo() { return orderNo; }
    public Long memberId() { return memberId; }
    public String invoiceNo() { return invoiceNo; }
}
