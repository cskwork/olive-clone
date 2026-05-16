package com.olive.commerce.batch;

import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.inventory.InventoryService;
import com.olive.commerce.order.Order;
import com.olive.commerce.order.OrderRepository;
import com.olive.commerce.order.OrderStatusHistory;
import com.olive.commerce.order.OrderStatusHistoryRepository;
import com.olive.commerce.payment.Payment;
import com.olive.commerce.payment.PaymentRepository;
import com.olive.commerce.payment.client.PgClient;
import com.olive.commerce.payment.client.dto.VerifyResponse;
import com.olive.commerce.promotion.CouponService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 결제 대기 만료 배치 작업 (PRD §17).
 * <p>
 * 5분마다 실행: PAYMENT_PENDING 상태로 30분 이상 경과된 주문을 검증하고,
 * PG 상태가 FAIL/UNKNOWN이면 주문을 FAILED로 전이하고 재고/쿠폰을 복구합니다.
 */
@Component
@RequiredArgsConstructor
public class PaymentPendingExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentPendingExpiryJob.class);

    private static final String JOB_NAME = "paymentPendingExpiry";
    private static final int PENDING_MINUTES = 30;

    private final JobRunTracker jobRunTracker;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final InventoryService inventoryService;
    private final CouponService couponService;

    /**
     * 결제 대기 만료 처리 (매 5분).
     * <p>
     * ShedLock 락: 최대 55분 보장 (기본값).
     */
    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "PaymentPendingExpiryJob", lockAtMostFor = "54m", lockAtLeastFor = "1m")
    public void expirePaymentPending() {
        JobRun jobRun = jobRunTracker.start(JOB_NAME, JobRun.TriggeredBy.SCHEDULED);
        int processedCount = 0;
        String errorMessage = null;

        try {
            processedCount = expirePendingPayments();
            jobRunTracker.complete(jobRun, processedCount);

        } catch (Exception e) {
            errorMessage = e.getMessage();
            jobRunTracker.fail(jobRun, errorMessage, processedCount);
            log.error("[{}] Job execution failed: {}", JOB_NAME, e.getMessage(), e);
        }
    }

    /**
     * 결제 대기 만료 실제 처리 로직.
     *
     * @return 검증 대상이 된 결제 수
     */
    @Transactional
    public int expirePendingPayments() {
        OffsetDateTime cutoffTime = OffsetDateTime.now().minusMinutes(PENDING_MINUTES);

        // PAYMENT_PENDING 상태로 30분 이상 경과된 주문의 결제를 조회
        List<Payment> stalePayments = paymentRepository.findPendingPaymentsOlderThan(cutoffTime);
        int processedCount = stalePayments.size();

        for (Payment payment : stalePayments) {
            processPayment(payment);
        }

        return processedCount;
    }

    /**
     * 개별 결제 검증 처리.
     */
    @Transactional
    public void processPayment(Payment payment) {
        Long orderId = payment.getOrder().getId();
        String paymentKey = payment.getPaymentKey();

        if (paymentKey == null || paymentKey.isBlank()) {
            log.warn("Payment has no paymentKey, marking as FAILED: orderId={}", orderId);
            markOrderAsFailed(orderId, "No payment key assigned");
            return;
        }

        try {
            // PG 상태 검증
            VerifyResponse response = pgClient.verify(paymentKey);

            if ("APPROVED".equals(response.status())) {
                // PG는 승인되었으나 우리 시스템은 아직 PAYMENT_PENDING
                // 웹훅이 도착하지 않은 경우로 간주하고 로그만 남김
                log.info("Payment APPROVED in PG but still PENDING in our system: orderId={}, paymentKey={}", orderId, paymentKey);
                // 추후 웹훅이 도착하면 정상 처리될 것임
            } else if ("FAILED".equals(response.status()) || "CANCELED".equals(response.status())) {
                // PG에서 실패/취소됨
                log.info("Payment failed in PG, marking order as FAILED: orderId={}, paymentKey={}, pgStatus={}",
                        orderId, paymentKey, response.status());
                markOrderAsFailed(orderId, "PG status: " + response.status());
            } else {
                // UNKNOWN或其他 상태
                log.warn("Payment has unknown status in PG, marking as FAILED: orderId={}, paymentKey={}, pgStatus={}",
                        orderId, paymentKey, response.status());
                markOrderAsFailed(orderId, "PG unknown status: " + response.status());
            }

        } catch (Exception e) {
            log.error("Failed to verify payment with PG, marking as FAILED: orderId={}, paymentKey={}, error={}",
                    orderId, paymentKey, e.getMessage());
            markOrderAsFailed(orderId, "PG verification error: " + e.getMessage());
        }
    }

    /**
     * 주문을 FAILED 상태로 변경하고 재고/쿠폰을 복구합니다.
     * <p>
     * NOTE: 포인트는 복구하지 않음 (OLV-072: point.use는 PAID 시점에만 실행됨).
     */
    private void markOrderAsFailed(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(com.olive.commerce.common.error.ErrorCode.ORDER_NOT_FOUND,
                        "orderId=" + orderId));

        // 이미 종단 상태이면 무시
        if (order.getStatus() == Order.OrderStatus.FAILED ||
            order.getStatus() == Order.OrderStatus.CANCELED) {
            log.info("Order already in terminal state, skipping: orderId={}, status={}", orderId, order.getStatus());
            return;
        }

        Order.OrderStatus fromStatus = order.getStatus();

        // 1. 재고 예약 해제
        try {
            inventoryService.release(orderId, reason);
        } catch (Exception e) {
            log.warn("Failed to release inventory for order {}: {}", orderId, e.getMessage());
        }

        // 2. 쿠폰 복구
        if (order.getUsedMemberCouponId() != null) {
            try {
                couponService.restore(order.getUsedMemberCouponId(), orderId);
            } catch (Exception e) {
                log.warn("Failed to restore coupon for order {}: {}", orderId, e.getMessage());
            }
        }

        // 3. 주문 상태를 FAILED로 변경
        order.toFailed();
        orderRepository.save(order);

        // 4. 상태 이력 기록
        OrderStatusHistory history = OrderStatusHistory.transition(
                order,
                fromStatus.name(),
                Order.OrderStatus.FAILED.name(),
                OrderStatusHistory.ChangedByKind.SYSTEM,
                null,
                reason
        );
        orderStatusHistoryRepository.save(history);

        log.info("Order marked as FAILED: orderId={}, orderNo={}, reason={}", orderId, order.getOrderNo(), reason);
    }
}
