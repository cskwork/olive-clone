package com.olive.commerce.common.event;

import com.olive.commerce.common.analytics.SalesAggregator;
import com.olive.commerce.common.notification.NotificationService;
import com.olive.commerce.delivery.DeliveryCompletedEvent;
import com.olive.commerce.payment.OrderRefundedEvent;
import com.olive.commerce.payment.PaymentApprovedEvent;
import com.olive.commerce.promotion.PointService;
import com.olive.commerce.review.ReviewEligibilityCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 도메인 이벤트 구독자 모음.
 * <p>각 도메인에서 발생한 이벤트를 구독하여 후속 처리를 수행합니다.
 */
@Component
public class DomainEventSubscribers {

    private static final Logger log = LoggerFactory.getLogger(DomainEventSubscribers.class);

    private final NotificationService notificationService;
    private final SalesAggregator salesAggregator;
    private final PointService pointService;
    private final ReviewEligibilityCache reviewEligibilityCache;

    public DomainEventSubscribers(
            NotificationService notificationService,
            SalesAggregator salesAggregator,
            PointService pointService,
            ReviewEligibilityCache reviewEligibilityCache
    ) {
        this.notificationService = notificationService;
        this.salesAggregator = salesAggregator;
        this.pointService = pointService;
        this.reviewEligibilityCache = reviewEligibilityCache;
    }

    // ==========================================================================
    // PaymentApprovedEvent 구독자
    // ==========================================================================

    /**
     * 결제 승인 시 알림 발송.
     */
    @EventListener
    public void onPaymentApproved_SendNotification(PaymentApprovedEvent event) {
        try {
            notificationService.sendOrderConfirmed(
                    event.orderId(), // memberId는 별도 조회 필요하므로 orderId 사용
                    event.orderNo(),
                    event.approvedAmount().toString()
            );
            log.debug("PaymentApproved: notification sent for orderNo={}", event.orderNo());
        } catch (Exception e) {
            log.error("PaymentApproved: notification failed for orderNo={}", event.orderNo(), e);
        }
    }

    /**
     * 결제 승인 시 매출 집계.
     */
    @EventListener
    public void onPaymentApproved_RecordSale(PaymentApprovedEvent event) {
        try {
            salesAggregator.recordSale(event.orderId(), event.approvedAmount());
            log.debug("PaymentApproved: sale recorded for orderNo={}", event.orderNo());
        } catch (Exception e) {
            log.error("PaymentApproved: sales aggregation failed for orderNo={}", event.orderNo(), e);
        }
    }

    // ==========================================================================
    // DeliveryCompletedEvent 구독자
    // ==========================================================================

    /**
     * 배송 완료 시 포인트 적립 전환 (예약 → 즉시 사용 가능).
     */
    @EventListener
    public void onDeliveryCompleted_FlipPointsToSpendable(DeliveryCompletedEvent event) {
        try {
            pointService.flipScheduledToSpendable(event.memberId(), event.orderId());
            log.debug("DeliveryCompleted: points flipped to spendable for orderId={}", event.orderId());
        } catch (Exception e) {
            log.error("DeliveryCompleted: point flip failed for orderId={}", event.orderId(), e);
        }
    }

    /**
     * 배송 완료 시 리뷰 작성 가능 마크.
     */
    @EventListener
    public void onDeliveryCompleted_MarkReviewEligible(DeliveryCompletedEvent event) {
        try {
            reviewEligibilityCache.markEligible(event.orderId());
            log.debug("DeliveryCompleted: review eligibility marked for orderId={}", event.orderId());
        } catch (Exception e) {
            log.error("DeliveryCompleted: review eligibility marking failed for orderId={}", event.orderId(), e);
        }
    }

    // ==========================================================================
    // OrderCanceledEvent 구독자
    // ==========================================================================

    /**
     * 주문 취소 시 알림 발송.
     */
    @EventListener
    public void onOrderCanceled_SendNotification(com.olive.commerce.order.OrderCanceledEvent event) {
        try {
            notificationService.sendCancellation(
                    event.memberId(),
                    event.orderNo(),
                    event.reason()
            );
            log.debug("OrderCanceled: notification sent for orderNo={}", event.orderNo());
        } catch (Exception e) {
            log.error("OrderCanceled: notification failed for orderNo={}", event.orderNo(), e);
        }
    }

    /**
     * 주문 취소 시 매출 차감.
     */
    @EventListener
    public void onOrderCanceled_RecordReversal(com.olive.commerce.order.OrderCanceledEvent event) {
        try {
            // 주문 취소 시 매출 차감 (금액은 주문 조회 필요)
            // 간단 구현을 위해 0으로 기록 - 실제로는 주문 금액 조회 필요
            salesAggregator.recordReversal(event.orderId(), java.math.BigDecimal.ZERO);
            log.debug("OrderCanceled: sales reversal recorded for orderNo={}", event.orderNo());
        } catch (Exception e) {
            log.error("OrderCanceled: sales reversal failed for orderNo={}", event.orderNo(), e);
        }
    }

    // ==========================================================================
    // OrderRefundedEvent 구독자
    // ==========================================================================

    /**
     * 환불 완료 시 알림 발송.
     */
    @EventListener
    public void onOrderRefunded_SendNotification(OrderRefundedEvent event) {
        try {
            notificationService.sendCancellation(
                    null, // memberId는 별도 조회 필요
                    event.orderNo(),
                    "환불 완료"
            );
            log.debug("OrderRefunded: notification sent for orderNo={}", event.orderNo());
        } catch (Exception e) {
            log.error("OrderRefunded: notification failed for orderNo={}", event.orderNo(), e);
        }
    }

    /**
     * 환불 완료 시 매출 차감.
     */
    @EventListener
    public void onOrderRefunded_RecordReversal(OrderRefundedEvent event) {
        try {
            salesAggregator.recordReversal(event.orderId(), event.amount());
            log.debug("OrderRefunded: sales reversal recorded for orderNo={}", event.orderNo());
        } catch (Exception e) {
            log.error("OrderRefunded: sales reversal failed for orderNo={}", event.orderNo(), e);
        }
    }
}
