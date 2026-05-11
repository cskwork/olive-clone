package com.olive.commerce.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.inventory.InventoryService;
import com.olive.commerce.order.Order;
import com.olive.commerce.order.OrderRepository;
import com.olive.commerce.order.OrderStatusHistory;
import com.olive.commerce.order.OrderStatusHistoryRepository;
import com.olive.commerce.payment.Payment.PaymentStatus;
import com.olive.commerce.payment.PaymentTransaction.TransactionKind;
import com.olive.commerce.payment.client.PgClient;
import com.olive.commerce.payment.client.dto.ConfirmRequest;
import com.olive.commerce.payment.client.dto.ConfirmResponse;
import com.olive.commerce.payment.client.exception.PgTimeoutException;
import com.olive.commerce.promotion.CouponService;
import com.olive.commerce.promotion.PointService;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Payment 도메인 서비스 (OLV-072).
 * <p>
 * 8단계 결제 확인 파이프라인 구현 (PRD §8.4).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;

    private final PgClient pgClient;
    private final InventoryService inventoryService;
    private final CouponService couponService;
    private final PointService pointService;

    private final AuditLogger auditLogger;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private static final OffsetDateTime POINTS_AVAILABLE_DEFAULT = OffsetDateTime.now(ZoneOffset.UTC).plusDays(14);
    private static final OffsetDateTime POINTS_EXPIRES_DEFAULT = OffsetDateTime.now(ZoneOffset.UTC).plusYears(1);

    /**
     * 결제 확인 (PRD §8.4 8단계 파이프라인).
     *
     * @param request         결제 확인 요청
     * @param idempotencyKey 멱등성 키 (선택)
     * @return 결제 확인 응답
     * @throws PgTimeoutException PG 타임아웃 시 (504 반환)
     */
    @Transactional
    public PaymentDtos.ConfirmResponse confirmPayment(PaymentDtos.ConfirmRequest request, UUID idempotencyKey) {
        // Step 1: 주문 조회
        Order order = orderRepository.findByOrderNo(request.orderNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                        "orderNo=" + request.orderNo()));

        // Step 1-2: 주문 상태 검증
        if (order.getStatus() != Order.OrderStatus.PAYMENT_PENDING) {
            // 이미 PAID 상태면 멱등적으로 200 반환 (PG 재호출 금지)
            if (order.getStatus() == Order.OrderStatus.PAID) {
                Payment existingPayment = paymentRepository.findByOrderId(order.getId())
                        .orElse(null);
                if (existingPayment != null && existingPayment.getStatus() == PaymentStatus.APPROVED) {
                    log.info("Order already paid: orderNo={}, paymentKey={}",
                            request.orderNo(), existingPayment.getPaymentKey());
                    return new PaymentDtos.ConfirmResponse(
                            order.getId(),
                            order.getOrderNo(),
                            order.getStatus().name(),
                            existingPayment.getPaymentKey()
                    );
                }
            }
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Order is not in PAYMENT_PENDING: " + order.getStatus());
        }

        // Step 1-3: Payment 로드
        Payment payment = paymentRepository.findByOrderIdAndStatus(order.getId(), PaymentStatus.REQUESTED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "No REQUESTED payment found for order: " + order.getId()));

        // Step 2 (Idempotency): idempotencyKey로 이전 트랜잭션 검색
        if (idempotencyKey != null) {
            var existingTransaction = transactionRepository.findByPaymentIdAndKindAndIdempotencyKey(
                    payment.getId(),
                    TransactionKind.APPROVE,
                    idempotencyKey
            );
            if (existingTransaction.isPresent()) {
                log.info("Idempotent request: returning cached response for paymentId={}, idempotencyKey={}",
                        payment.getId(), idempotencyKey);
                return buildConfirmResponse(order, payment);
            }
        }

        // Step 3: 결제 금액 검증
        if (payment.getRequestedAmount().compareTo(request.amount()) != 0) {
            auditLogger.log("PAYMENT_AMOUNT_MISMATCH", Map.of(
                    "orderId", order.getId(),
                    "orderNo", order.getOrderNo(),
                    "requestedAmount", payment.getRequestedAmount(),
                    "requestAmount", request.amount(),
                    "orderFinalAmount", order.getFinalPaymentAmount()
            ));
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    String.format("Amount mismatch: requested=%s, order=%s, payment=%s",
                            payment.getRequestedAmount(),
                            order.getFinalPaymentAmount(),
                            request.amount()));
        }

        // orders.final_payment_amount와도 비교
        if (order.getFinalPaymentAmount().compareTo(request.amount()) != 0) {
            auditLogger.log("PAYMENT_AMOUNT_MISMATCH", Map.of(
                    "orderId", order.getId(),
                    "orderNo", order.getOrderNo(),
                    "orderFinalAmount", order.getFinalPaymentAmount(),
                    "requestAmount", request.amount()
            ));
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    String.format("Amount mismatch with order: order=%s, request=%s",
                            order.getFinalPaymentAmount(),
                            request.amount()));
        }

        // Step 4: PG사 결제 승인 API 호출
        ConfirmRequest pgRequest = new ConfirmRequest(
                request.paymentKey(),
                order.getId(),
                request.amount(),
                idempotencyKey != null ? idempotencyKey : UUID.randomUUID()
        );

        ConfirmResponse pgResponse;
        try {
            pgResponse = pgClient.confirmPayment(pgRequest);
        } catch (PgTimeoutException e) {
            // PG timeout: 504 반환, 상태 변화 없음
            log.warn("PG timeout for orderNo={}: {}", request.orderNo(), e.getMessage());
            throw e; // Controller에서 504로 변환
        }

        // Step 5: PG 응답 처리
        if ("FAILED".equals(pgResponse.status())) {
            // PG 실패: payments.status=FAILED, order 유지
            payment.fail(pgResponse.failedReason());
            paymentRepository.save(payment);
            recordTransaction(payment, TransactionKind.APPROVE, pgResponse, 500, idempotencyKey);
            log.info("PG returned FAILED for orderNo={}: {}", request.orderNo(), pgResponse.failedReason());
            // 200 반환 (PRD §9.3: 실패도 200으로, 클라이언트가 status 확인)
            return new PaymentDtos.ConfirmResponse(order.getId(), order.getOrderNo(),
                    order.getStatus().name(), null);
        }

        // Step 6: 결제 성공 처리 (Step 4-7: 단일 트랜잭션)
        processSuccessfulPayment(order, payment, pgResponse, request.paymentKey(), idempotencyKey);

        return buildConfirmResponse(order, payment);
    }

    /**
     * 결제 성공 시 처리 (Step 4-7).
     * <p>
     * 호출자의 트랜잭션 내에서 실행됨.
     */
    private void processSuccessfulPayment(
            Order order,
            Payment payment,
            ConfirmResponse pgResponse,
            String paymentKey,
            UUID idempotencyKey
    ) {
        OffsetDateTime now = OffsetDateTime.now();

        // Step 4-1: 주문 상태 PAID 변경
        order.toPaid();
        orderRepository.save(order);

        // Step 4-2: 결제 승인 상태 변경
        OffsetDateTime approvedAt = pgResponse.approvedAt() != null
                ? pgResponse.approvedAt().atZone(ZoneOffset.UTC).toOffsetDateTime()
                : OffsetDateTime.now();
        payment.approve(paymentKey, "mock", payment.getRequestedAmount(), approvedAt);
        paymentRepository.save(payment);

        // Step 4-3: 트랜잭션 기록
        recordTransaction(payment, TransactionKind.APPROVE, pgResponse, 200, idempotencyKey);

        // Step 4-4: 주문 상태 이력 기록
        OrderStatusHistory history = OrderStatusHistory.transition(
                order,
                Order.OrderStatus.PAYMENT_PENDING.name(),
                Order.OrderStatus.PAID.name(),
                OrderStatusHistory.ChangedByKind.SYSTEM,
                null,
                "결제 승인"
        );
        orderStatusHistoryRepository.save(history);

        // Step 5: 재고 선점 확정
        try {
            inventoryService.commit(order.getId());
        } catch (Exception e) {
            log.error("Failed to commit inventory for order {}: {}", order.getId(), e.getMessage());
            throw e;
        }

        // Step 6: 쿠폰 사용 처리
        if (order.getUsedMemberCouponId() != null) {
            try {
                couponService.markUsed(order.getUsedMemberCouponId(), order.getId());
            } catch (Exception e) {
                log.error("Failed to mark coupon used for order {}: {}", order.getId(), e.getMessage());
                throw e;
            }
        }

        // Step 7: 포인트 사용 처리
        if (order.getPointUsedAmount().compareTo(BigDecimal.ZERO) > 0) {
            try {
                pointService.use(order.getMemberId(), order.getPointUsedAmount(), order.getId());
            } catch (Exception e) {
                log.error("Failed to use points for order {}: {}", order.getId(), e.getMessage());
                throw e;
            }
        }

        // Step 7-1: 포인트 적립 예약
        // 배송 완료 기준이지만 일단 +14d로 스케줄 (OLV-081에서 이벤트로 갱신)
        try {
            pointService.earnScheduled(
                    order.getMemberId(),
                    calculateEarnedPoints(order.getFinalPaymentAmount()),
                    order.getId(),
                    POINTS_AVAILABLE_DEFAULT,
                    POINTS_EXPIRES_DEFAULT
            );
        } catch (Exception e) {
            log.error("Failed to schedule earned points for order {}: {}", order.getId(), e.getMessage());
            // 포인트 적립 실패는 주문을 실패시키지 않음
        }

        // Step 8: Outbox 이벤트 생성 (같은 트랜잭션)
        try {
            String payloadJson = objectMapper.writeValueAsString(Map.of(
                    "orderId", order.getId(),
                    "orderNo", order.getOrderNo(),
                    "paymentId", payment.getId(),
                    "paymentKey", payment.getPaymentKey(),
                    "approvedAmount", payment.getApprovedAmount()
            ));
            OutboxEvent outboxEvent = OutboxEvent.create(
                    "PAYMENT",
                    payment.getId(),
                    "PAYMENT_APPROVED",
                    payloadJson
            );
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to create outbox event for payment {}: {}", payment.getId(), e.getMessage());
        }

        // Audit log
        auditLogger.log("PAYMENT_APPROVED", Map.of(
                "orderId", order.getId(),
                "orderNo", order.getOrderNo(),
                "paymentId", payment.getId(),
                "amount", payment.getApprovedAmount()
        ));

        // Spring 이벤트 발행 (AFTER_COMMIT 리스너가 처리)
        eventPublisher.publishEvent(new PaymentApprovedEvent(
                this,
                order.getId(),
                order.getOrderNo(),
                payment.getId(),
                payment.getPaymentKey(),
                payment.getApprovedAmount()
        ));
    }

    /**
     * PG 트랜잭션 기록.
     */
    private void recordTransaction(Payment payment, TransactionKind kind,
                                     ConfirmResponse pgResponse, int httpStatus,
                                     UUID idempotencyKey) {
        try {
            String responseJson = objectMapper.writeValueAsString(Map.of(
                    "status", pgResponse.status(),
                    "approvedAt", pgResponse.approvedAt(),
                    "failedReason", pgResponse.failedReason()
            ));
            PaymentTransaction transaction = PaymentTransaction.clientRequest(
                    payment, kind, responseJson, httpStatus, idempotencyKey
            );
            transactionRepository.save(transaction);
        } catch (Exception e) {
            log.error("Failed to record transaction for payment {}: {}", payment.getId(), e.getMessage());
        }
    }

    /**
     * 적립 포인트 계산 (간단 구현: 1% 적립).
     */
    private BigDecimal calculateEarnedPoints(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(0.01))
                .setScale(0, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * ConfirmResponse 빌더.
     */
    private PaymentDtos.ConfirmResponse buildConfirmResponse(Order order, Payment payment) {
        return new PaymentDtos.ConfirmResponse(
                order.getId(),
                order.getOrderNo(),
                order.getStatus().name(),
                payment.getPaymentKey()
        );
    }

    /**
     * PaymentApprovedEvent AFTER_COMMIT 리스너.
     * <p>
     * 실제 이벤트 처리는 outbox 워커가 담당.
     */
    @TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        log.info("Payment approved event published: orderId={}, orderNo={}, paymentId={}",
                event.orderId(), event.orderNo(), event.paymentId());
    }
}
