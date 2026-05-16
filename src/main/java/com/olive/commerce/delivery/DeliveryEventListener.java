package com.olive.commerce.delivery;

import com.olive.commerce.payment.PaymentApprovedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 배송 도메인 이벤트 리스너.
 */
@Component
public class DeliveryEventListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEventListener.class);

    private final DeliveryService deliveryService;

    public DeliveryEventListener(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /**
     * 결제 승인 이벤트를 수신하여 배송을 준비합니다.
     * AFTER_COMMIT 페이즈에 실행되어 outbox 이벤트와 함께 fan-out됩니다.
     * <p>
     * Note: @Async 메서드는 별도 스레드에서 실행되므로 DeliveryService의
     * @Transactional 메서드가 새로운 트랜잭션을 시작하도록 REQUIRES_NEW를 사용합니다.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        log.info("PaymentApprovedEvent received: orderId={}, preparing delivery", event.orderId());
        try {
            Long deliveryId = deliveryService.prepareForOrder(event.orderId());

            // 배송 준비 후 즉시 운송장 발급 시도
            deliveryService.issueInvoice(deliveryId);

        } catch (Exception e) {
            log.error("Failed to prepare delivery for order: {}", event.orderId(), e);
        }
    }
}
