package com.olive.commerce.order;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 주문 생성 이벤트 (OLV-061, wiki §96-eventing).
 * <p>
 * 주문 생성 후 outbox에 기록되어 비동기로 fan-out됩니다.
 */
public class OrderCreatedEvent extends ApplicationEvent {

    private final Long orderId;
    private final String orderNo;
    private final Long memberId;
    private final BigDecimal finalPaymentAmount;
    private final List<OrderItemData> items;
    private final Long usedMemberCouponId;
    private final OffsetDateTime createdAt;

    public OrderCreatedEvent(
            Object source,
            Long orderId,
            String orderNo,
            Long memberId,
            BigDecimal finalPaymentAmount,
            List<OrderItemData> items,
            Long usedMemberCouponId,
            OffsetDateTime createdAt
    ) {
        super(source);
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.memberId = memberId;
        this.finalPaymentAmount = finalPaymentAmount;
        this.items = items;
        this.usedMemberCouponId = usedMemberCouponId;
        this.createdAt = createdAt;
    }

    public Long orderId() { return orderId; }
    public String orderNo() { return orderNo; }
    public Long memberId() { return memberId; }
    public BigDecimal finalPaymentAmount() { return finalPaymentAmount; }
    public List<OrderItemData> items() { return items; }
    public Long usedMemberCouponId() { return usedMemberCouponId; }
    public OffsetDateTime createdAt() { return createdAt; }

    /**
     * 주문 상품 데이터 (이벤트 페이로드용).
     */
    public record OrderItemData(
            Long productId,
            Long productOptionId,
            int quantity,
            BigDecimal unitPrice
    ) {}
}
