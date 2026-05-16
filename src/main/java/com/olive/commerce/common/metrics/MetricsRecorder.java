package com.olive.commerce.common.metrics;

import com.olive.commerce.order.OrderCreatedEvent;
import com.olive.commerce.order.OrderCanceledEvent;
import com.olive.commerce.payment.PaymentApprovedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.olive.commerce.search.OutboxEventRepository;

/**
 * 도메인 이벤트를 수신하여 메트릭을 기록하는 컴포넌트 (OLV-130).
 *
 * <p>Spring ApplicationEvents를 사용하여 서비스 간 결합도를 낮춘다.
 */
@Component
@RequiredArgsConstructor
public class MetricsRecorder {

    private static final Logger log = LoggerFactory.getLogger(MetricsRecorder.class);

    private final CommerceMetrics metrics;
    private final OutboxEventRepository outboxEventRepository;

    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        metrics.orderCreated("CREATED");
        log.debug("Metric recorded: order_created for orderNo={}", event.orderNo());
    }

    @EventListener
    public void onOrderCanceled(OrderCanceledEvent event) {
        metrics.orderCanceled();
        log.debug("Metric recorded: order_canceled for orderNo={}", event.orderNo());
    }

    @EventListener
    public void onPaymentApproved(PaymentApprovedEvent event) {
        metrics.paymentAttempted("APPROVED", "MOCK"); // PG provider name (OLV-071 MockPgClient)
        log.debug("Metric recorded: payment_approved for orderNo={}", event.orderNo());
    }

    @Scheduled(fixedDelay = 5000)
    public void updateOutboxPendingCount() {
        try {
            long pendingCount = outboxEventRepository.countByEventTypeAndDlq("PRODUCT_INDEX_SYNC", false);
            metrics.setOutboxPendingCount(pendingCount);
        } catch (Exception e) {
            log.warn("Failed to update outbox_pending_count metric: {}", e.getMessage());
        }
    }
}
