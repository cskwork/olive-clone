package com.olive.commerce.inventory;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InventoryCommitFailedEvent} 수신 리스너.
 * <p>
 * {@link com.olive.commerce.common.event.OutboxEventDrainer} 가 PENDING 이벤트를
 * 픽업한 뒤 동기적으로 발행하므로, 트랜잭션 바운더리가 없는 컨텍스트에서 실행된다.
 * 따라서 {@code @TransactionalEventListener} 가 아닌 {@code @EventListener} 를 사용하며,
 * DB 쓰기를 위해 독자적인 {@code @Transactional} 트랜잭션을 시작한다.
 * <p>
 * 멱등성: {@link InventoryService#commit(Long)} 은 이미 COMMITTED 상태의 예약을 건너뛰므로
 * 이 리스너를 여러 번 호출해도 안전하다. 리스너가 예외를 전파하면 드레이너가
 * {@code attempt_count} 를 증가시키고, 5회 초과 시 DLQ로 이동한다.
 */
@Component
@RequiredArgsConstructor
public class InventoryCommitRetryListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryCommitRetryListener.class);

    private final InventoryService inventoryService;

    @EventListener
    @Transactional
    public void onInventoryCommitFailed(InventoryCommitFailedEvent event) {
        Long orderId = event.orderId();
        log.info("Retrying inventory commit for order {}", orderId);
        inventoryService.commit(orderId);
        log.info("Inventory commit retry succeeded for order {}", orderId);
    }
}
