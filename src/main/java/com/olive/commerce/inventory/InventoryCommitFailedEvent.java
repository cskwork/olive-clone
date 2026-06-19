package com.olive.commerce.inventory;

import org.springframework.context.ApplicationEvent;

/**
 * 재고 커밋 실패 이벤트.
 * <p>
 * PG 결제 승인 후 {@code InventoryService.commit()} 이 실패한 경우, 결제 승인을
 * 롤백하지 않고 이 이벤트를 outbox에 기록한다. {@link OutboxEventDrainer} 가
 * PENDING 이벤트를 픽업하여 이 이벤트를 발행하면, {@link InventoryCommitRetryListener}
 * 가 커밋을 재시도한다 — 이미 커밋된 예약은 멱등적으로 건너뛴다.
 */
public class InventoryCommitFailedEvent extends ApplicationEvent {

    private final Long orderId;

    public InventoryCommitFailedEvent(Object source, Long orderId) {
        super(source);
        this.orderId = orderId;
    }

    public Long orderId() { return orderId; }
}
