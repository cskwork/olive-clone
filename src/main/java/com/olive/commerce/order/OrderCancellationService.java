package com.olive.commerce.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.inventory.InventoryService;
import com.olive.commerce.payment.Payment;
import com.olive.commerce.payment.PaymentRepository;
import com.olive.commerce.payment.RefundRepository;
import com.olive.commerce.payment.client.PgClient;
import com.olive.commerce.payment.client.dto.CancelRequest;
import com.olive.commerce.payment.client.dto.CancelResponse;
import com.olive.commerce.promotion.CouponService;
import com.olive.commerce.promotion.PointService;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 주문 취소 서비스 (OLV-062).
 * <p>
 * 사용자/관리자 주문 취소와 PG 결제 취소, 재고 해제, 쿠폰/포인트 보상을
 * all-or-nothing(@Transactional)으로 처리한다. OrderService 파사드에서 분리한
 * 취소 협력자 (M1 하드닝 로직 포함).
 */
@Service
@RequiredArgsConstructor
public class OrderCancellationService {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationService.class);

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;

    private final InventoryService inventoryService;
    private final CouponService couponService;
    private final PointService pointService;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PgClient pgClient;

    private final AuditLogger auditLogger;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 사용자 주문 취소 (OLV-062).
     * <p>
     * PAYMENT_PENDING/PAID/PREPARING 상태에서만 취소 가능합니다.
     *
     * @param memberId 회원 ID
     * @param orderNo  주문 번호
     * @param reason   취소 사유 (선택)
     */
    @Transactional
    public void cancelUserOrder(Long memberId, String orderNo, String reason) {
        Order order = findAndValidateOwnership(memberId, orderNo);
        Order.OrderStatus fromStatus = order.getStatus();

        // 멱등성: 이미 취소된 주문이면 no-op (검증보다 먼저 체크)
        if (fromStatus == Order.OrderStatus.CANCELED) {
            log.info("Order already canceled: orderNo={}", orderNo);
            return;
        }

        validateCancellableStatus(order, OrderCanceledEvent.CancelKind.USER);

        executeCancel(order, reason != null ? reason : "사용자 취소", OrderCanceledEvent.CancelKind.USER);

        log.info("User order canceled: orderId={}, orderNo={}, memberId={}", order.getId(), orderNo, memberId);
    }

    /**
     * 관리자 강제 주문 취소 (OLV-062).
     * <p>
     * 비종단 상태(CANCELED/REFUNDED/FAILED 제외)에서 모두 취소 가능합니다.
     *
     * @param orderId 주문 ID (PK)
     * @param reason  취소 사유 (필수)
     * @param adminId  관리자 ID
     */
    @Transactional
    public void cancelAdminOrder(Long orderId, String reason, Long adminId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "orderId=" + orderId));

        Order.OrderStatus fromStatus = order.getStatus();

        // 멱등성: 이미 취소된 주문이면 no-op (검증보다 먼저 체크)
        if (fromStatus == Order.OrderStatus.CANCELED) {
            log.info("Order already canceled: orderId={}", orderId);
            return;
        }

        validateCancellableStatus(order, OrderCanceledEvent.CancelKind.ADMIN);

        executeCancel(order, reason != null ? reason : "관리자 취소", OrderCanceledEvent.CancelKind.ADMIN);

        auditLogger.log("ADMIN_CANCEL_ORDER", Map.of(
                "adminId", adminId != null ? adminId : "UNKNOWN",
                "orderId", orderId,
                "orderNo", order.getOrderNo(),
                "reason", reason
        ));

        log.info("Admin order canceled: orderId={}, orderNo={}, adminId={}", orderId, order.getOrderNo(), adminId);
    }

    /**
     * 주문 소유권 검증 및 조회.
     */
    private Order findAndValidateOwnership(Long memberId, String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "orderNo=" + orderNo));

        if (!order.getMemberId().equals(memberId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_OWNED,
                    "orderNo=" + orderNo + " does not belong to memberId=" + memberId);
        }

        return order;
    }

    /**
     * 취소 가능 상태 검증.
     */
    private void validateCancellableStatus(Order order, OrderCanceledEvent.CancelKind kind) {
        Order.OrderStatus status = order.getStatus();

        // 이미 종단 상태이면 취소 불가
        if (status == Order.OrderStatus.CANCELED ||
            status == Order.OrderStatus.REFUNDED ||
            status == Order.OrderStatus.FAILED) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE,
                    "Order is in terminal state: " + status);
        }

        // 사용자 취소: SHIPPING/DELIVERED/REFUND_REQUESTED 상태에서 불가
        if (kind == OrderCanceledEvent.CancelKind.USER) {
            if (status == Order.OrderStatus.SHIPPING ||
                status == Order.OrderStatus.DELIVERED ||
                status == Order.OrderStatus.REFUND_REQUESTED) {
                throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE,
                        "User cannot cancel order in state: " + status + " (use return flow)");
            }
        }
    }

    /**
     * 취소 실행 (공통 로직).
     */
    private void executeCancel(Order order, String reason, OrderCanceledEvent.CancelKind kind) {
        Order.OrderStatus fromStatus = order.getStatus();
        Long orderId = order.getId();

        // 1. PG 결제 취소 (PAID/PREPARING 상태인 경우 — 이미 청구된 금액을 PG에서 되돌림).
        // 저장된 paymentKey로 PG를 호출하고 payments 행을 CANCELED로 전이한다.
        // CREATED/PAYMENT_PENDING은 아직 청구 전이므로 PG 취소를 호출하지 않는다.
        if (fromStatus == Order.OrderStatus.PAID || fromStatus == Order.OrderStatus.PREPARING) {
            cancelPaymentAtPg(order, reason);
        }

        // 2. 재고 예약 해제
        try {
            inventoryService.release(orderId, reason);
        } catch (Exception e) {
            log.warn("Failed to release inventory for order {}: {}", orderId, e.getMessage());
            // 계속 진행: 재고 해제 실패가 주문 취소를 막아서는 안 됨
        }

        // 3. 쿠폰 복구.
        // 보상 실패는 삼키지 않는다 — 이 메서드는 @Transactional이므로 예외를 전파하여
        // 취소 전체를 롤백한다(all-or-nothing). 부분 보상으로 dirty state가 남지 않도록 한다.
        if (order.getUsedMemberCouponId() != null) {
            couponService.restore(order.getUsedMemberCouponId(), orderId);
        }

        // 4. 포인트 복구 (동일 정책: 실패 시 전파 → 롤백).
        pointService.cancel(order.getMemberId(), orderId);

        // 5. 상태 전이 (관리자는 강제 취소, 사용자는 일반 취소)
        if (kind == OrderCanceledEvent.CancelKind.ADMIN) {
            order.forceCanceled(reason);
        } else {
            order.toCanceled(reason);
        }
        orderRepository.save(order);

        // 6. 감사 이력 기록
        OrderStatusHistory.ChangedByKind changedByKind = kind == OrderCanceledEvent.CancelKind.ADMIN
                ? OrderStatusHistory.ChangedByKind.ADMIN
                : OrderStatusHistory.ChangedByKind.USER;
        Long changedById = kind == OrderCanceledEvent.CancelKind.ADMIN ? null : order.getMemberId();

        OrderStatusHistory history = OrderStatusHistory.transition(
                order,
                fromStatus.name(),
                Order.OrderStatus.CANCELED.name(),
                changedByKind,
                changedById,
                reason
        );
        orderStatusHistoryRepository.save(history);

        // 7. Outbox 이벤트 발행
        try {
            String payloadJson = objectMapper.writeValueAsString(
                    Map.of(
                            "orderId", orderId,
                            "orderNo", order.getOrderNo(),
                            "memberId", order.getMemberId(),
                            "reason", reason,
                            "fromStatus", fromStatus.name(),
                            "cancelKind", kind.name()
                    )
            );
            OutboxEvent outboxEvent = OutboxEvent.create(
                    "ORDER",
                    orderId,
                    "ORDER_CANCELED",
                    payloadJson
            );
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.warn("Failed to create outbox event for order {}: {}", orderId, e.getMessage());
        }

        // 8. Audit log
        auditLogger.log("ORDER_CANCELED", Map.of(
                "orderId", orderId,
                "orderNo", order.getOrderNo(),
                "memberId", order.getMemberId(),
                "reason", reason,
                "fromStatus", fromStatus.name(),
                "cancelKind", kind.name()
        ));

        // 9. Spring 이벤트 발행
        eventPublisher.publishEvent(new OrderCanceledEvent(
                this,
                orderId,
                order.getOrderNo(),
                order.getMemberId(),
                reason,
                fromStatus,
                kind
        ));
    }

    /**
     * PAID/PREPARING 주문의 PG 결제를 취소한다 (이미 청구된 금액 환원).
     * <p>
     * 저장된 paymentKey로 PG cancel을 호출하고, payments 행을 CANCELED로 전이한다.
     * PG 호출 실패는 삼키지 않고 전파하여(트랜잭션 롤백) 결제가 살아있는 채로
     * 주문만 취소되는 불일치를 막는다.
     */
    private void cancelPaymentAtPg(Order order, String reason) {
        Long orderId = order.getId();
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "No payment found for order: " + orderId));

        // 이미 취소/환불된 결제는 PG 재호출 없이 멱등 처리
        if (payment.getStatus() == Payment.PaymentStatus.CANCELED
                || payment.getStatus() == Payment.PaymentStatus.REFUNDED) {
            log.info("Payment already {} for order {}, skipping PG cancel", payment.getStatus(), orderId);
            return;
        }

        String paymentKey = order.getPaymentKey() != null ? order.getPaymentKey() : payment.getPaymentKey();
        if (paymentKey == null) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                    "No paymentKey stored for paid order: " + orderId);
        }

        // 이미 승인된 부분 환불을 제외한 잔여 청구액만 PG에서 취소한다.
        // order.finalPaymentAmount를 그대로 보내면 부분 환불된 만큼 이중 환급이 발생한다.
        java.math.BigDecimal paidAmount = payment.getApprovedAmount() != null
                ? payment.getApprovedAmount()
                : order.getFinalPaymentAmount();
        java.math.BigDecimal alreadyRefunded = refundRepository.sumApprovedAmountByPaymentId(payment.getId());
        java.math.BigDecimal cancelAmount = paidAmount.subtract(alreadyRefunded);

        // 이미 전액 환불되었으면 PG 재호출 없이 payment만 CANCELED로 정리한다.
        if (cancelAmount.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            log.info("Nothing left to cancel at PG for order {} (paid={}, refunded={}), marking payment CANCELED",
                    orderId, paidAmount, alreadyRefunded);
            payment.setStatus(Payment.PaymentStatus.CANCELED);
            payment.setFailedReason(reason);
            paymentRepository.save(payment);
            return;
        }

        CancelResponse pgResponse = pgClient.cancelPayment(new CancelRequest(
                paymentKey,
                cancelAmount,
                reason
        ));

        payment.setStatus(Payment.PaymentStatus.CANCELED);
        payment.setFailedReason(reason);
        paymentRepository.save(payment);

        log.info("PG cancel completed for order {}: paymentKey={}, cancelAmount={}, alreadyRefunded={}, pgStatus={}",
                orderId, paymentKey, cancelAmount, alreadyRefunded, pgResponse.status());
    }
}
