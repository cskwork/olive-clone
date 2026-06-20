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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Payment 도메인 서비스 (OLV-072).
 * <p>
 * 8단계 결제 확인 파이프라인 구현 (PRD §8.4).
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final RefundRepository refundRepository;
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
    private final StringRedisTemplate redisTemplate;
    private final PaymentTransactionRecorder transactionRecorder;
    private final InventoryCommitFailureRecorder inventoryCommitFailureRecorder;

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
            transactionRecorder.record(payment, TransactionKind.APPROVE, pgResponse, 500, idempotencyKey);
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

        // Step 4-1: 주문 상태 PAID 변경 + PG paymentKey 저장 (취소 시 PG 취소 호출에 사용)
        order.toPaid();
        order.setPaymentKey(paymentKey);
        orderRepository.save(order);

        // Step 4-2: 결제 승인 상태 변경
        OffsetDateTime approvedAt = pgResponse.approvedAt() != null
                ? pgResponse.approvedAt().atZone(ZoneOffset.UTC).toOffsetDateTime()
                : OffsetDateTime.now();
        payment.approve(paymentKey, "mock", payment.getRequestedAmount(), approvedAt);
        paymentRepository.save(payment);

        // Step 4-3: 트랜잭션 기록
        transactionRecorder.record(payment, TransactionKind.APPROVE, pgResponse, 200, idempotencyKey);

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

        // Step 5: 재고 선점 확정.
        // PG 승인(order=PAID, payment=APPROVED, APPROVE 트랜잭션 로그)은 이 트랜잭션에서
        // 이미 영속화되었다. 재고 커밋 실패가 이 트랜잭션을 롤백하면 PG 승인 기록이 사라지고,
        // 재시도 시 멱등성 검사(Step 2)가 APPROVE 트랜잭션을 찾지 못해 PG를 다시 호출 → 이중 결제 위험.
        // 따라서 재고 커밋 실패는 결제 승인을 롤백시키지 않고, 보정용 outbox 이벤트로 기록한다.
        // 재시도는 "이미 PAID + APPROVED" 멱등 분기로 200을 반환하며 재결제하지 않는다.
        try {
            inventoryService.commit(order.getId());
        } catch (Exception e) {
            log.error("Failed to commit inventory for order {} (payment already approved, recording for compensation): {}",
                    order.getId(), e.getMessage());
            recordInventoryCommitFailure(order, payment, e);
        }

        // Note: 쿠폰 사용과 포인트 사용은 OrderService.createOrder에서 이미 처리됨
        // PaymentService에서는 재고 커밋과 상태 변경만 수행

        // Step 6: 포인트 적립 예약
        // 배송 완료 기준이지만 일단 +14d로 스케줄 (OLV-081에서 이벤트로 갱신)
        try {
            pointService.earnScheduled(
                    order.getMemberId(),
                    calculateEarnedPoints(order.getFinalPaymentAmount()),
                    order.getId(),
                    now.plusDays(14),
                    now.plusYears(1)
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
     * 재고 커밋 실패를 보정용 outbox 이벤트로 기록.
     * <p>
     * PG 승인은 이미 확정되었으므로 결제 트랜잭션을 롤백하지 않는다. 보정 이벤트가
     * 결제 트랜잭션과 함께 사라지지 않도록 {@link InventoryCommitFailureRecorder}가
     * REQUIRES_NEW로 독립 커밋한다. 그 독립 트랜잭션마저 실패하면(드문 경우) 결제 승인은
     * 보존하되 운영 알림용 구조화 ERROR 로그를 남긴다 — 보정 신호가 조용히 사라지지 않게 한다.
     */
    private void recordInventoryCommitFailure(Order order, Payment payment, Exception cause) {
        try {
            inventoryCommitFailureRecorder.record(order, payment, cause);
        } catch (Exception e) {
            log.error("ALERT inventory-commit compensation could not be durably recorded for payment {} "
                            + "(payment already approved, NOT rolling back) — manual recovery required: {}",
                    payment.getId(), e.getMessage(), e);
        }
    }

    /**
     * 적립 포인트 계산 (간단 구현: 1% 적립).
     */
    private BigDecimal calculateEarnedPoints(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(0.01))
                .setScale(0, RoundingMode.HALF_UP);
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

    /**
     * PG 웹훅 처리 (OLV-073).
     * <p>
     * PG사가 비동기로 결제 상태 변경을 푸시하는 엔드포인트.
     *
     * @param request   웹훅 요청
     * @param signature 웹훅 서명 (HMAC-SHA256)
     * @return 웹훅 처리 응답 (항상 200)
     */
    @Transactional
    public PaymentDtos.WebhookResponse handleWebhook(PaymentDtos.WebhookRequest request,
                                                     String signature,
                                                     String rawPayload) {
        // Step 1: raw body 서명 검증. 실패 요청이 dedup key를 오염시키지 않도록 가장 먼저 한다.
        if (!pgClient.verifyWebhookSignature(rawPayload, signature)) {
            log.warn("Invalid webhook signature for paymentKey={}", request.paymentKey());
            throw new BusinessException(ErrorCode.PG_WEBHOOK_INVALID,
                    "Invalid webhook signature");
        }

        // Step 2: 결제 조회
        Payment payment = paymentRepository.findByPaymentKey(request.paymentKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "Payment not found for paymentKey: " + request.paymentKey()));

        String dedupKey = "webhook:dedup:" + request.paymentKey() + ":" + request.status();
        Boolean dedupResult = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", Duration.ofMinutes(5));
        if (Boolean.FALSE.equals(dedupResult)) {
            log.info("Duplicate webhook received: paymentKey={}, status={}",
                    request.paymentKey(), request.status());
            return new PaymentDtos.WebhookResponse(false, "Duplicate webhook (already processed)");
        }

        // Step 3: 유효한 웹훅은 트랜잭션 기록 (사후 분석용)
        transactionRecorder.recordWebhook(payment, request, signature);

        // Step 4: 상태별 처리
        switch (request.status()) {
            case "APPROVED" -> handleApprovedWebhook(payment, request);
            case "FAILED", "CANCELED" -> handleFailedWebhook(payment, request);
            case "REFUNDED" -> handleRefundedWebhook(payment, request);
            default -> log.warn("Unknown webhook status: {}", request.status());
        }

        return new PaymentDtos.WebhookResponse(true, "Webhook processed successfully");
    }

    /**
     * APPROVED 웹훅 처리.
     * <p>
     * 주문이 PAYMENT_PENDING이면 confirm 로직과 동일하게 처리.
     * 이미 PAID면 중복 로그만 남기고 no-op.
     */
    private void handleApprovedWebhook(Payment payment, PaymentDtos.WebhookRequest request) {
        Order order = payment.getOrder();

        // 이미 APPROVED 상태면 no-op
        if (payment.getStatus() == PaymentStatus.APPROVED) {
            log.info("Payment already approved: paymentKey={}, orderNo={}",
                    request.paymentKey(), order.getOrderNo());
            return;
        }

        // 주문이 PAYMENT_PENDING이 아니면 경고 로그
        if (order.getStatus() != Order.OrderStatus.PAYMENT_PENDING) {
            log.warn("Order not in PAYMENT_PENDING when webhook APPROVED received: orderNo={}, status={}",
                    order.getOrderNo(), order.getStatus());
        }

        // 결제 승인 처리 (confirm 경로와 동일)
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime approvedAt = request.approvedAt() != null
                ? request.approvedAt().atZone(ZoneOffset.UTC).toOffsetDateTime()
                : now;

        processSuccessfulPayment(
                order,
                payment,
                new ConfirmResponse("APPROVED", request.approvedAt(), null),
                request.paymentKey(),
                null  // idempotencyKey 없음 (PG-originated)
        );

        log.info("Webhook APPROVED processed: orderNo={}, paymentKey={}",
                order.getOrderNo(), request.paymentKey());
    }

    /**
     * FAILED/CANCELED 웹훅 처리.
     * <p>
     * 결제 상태를 FAILED/CANCELED로 변경.
     * 주문 상태는 PAYMENT_PENDING 유지 (사용자 취소 가능).
     * 재고 해제는 배치에서 처리 (OLV-120).
     */
    private void handleFailedWebhook(Payment payment, PaymentDtos.WebhookRequest request) {
        Order order = payment.getOrder();

        PaymentStatus newStatus = "CANCELED".equals(request.status())
                ? PaymentStatus.CANCELED
                : PaymentStatus.FAILED;

        payment.setStatus(newStatus);
        payment.setFailedReason(request.failedReason());
        paymentRepository.save(payment);

        // 주문 상태는 PAYMENT_PENDING 유지 (사용자가 취소 가능)
        // PG 재시도 윈도우가 지났으면 FAILED로 변경 (배치 정책에 따름)
        log.info("Webhook {} processed: orderNo={}, paymentKey={}, reason={}",
                newStatus, order.getOrderNo(), request.paymentKey(),
                request.failedReason());
    }

    /**
     * REFUNDED 웹훅 처리.
     * <p>
     * 주문이 REFUND_REQUESTED 상태일 때만 유효.
     * RefundService.approveRefund()에서 이미 처리된 경우 무시.
     */
    private void handleRefundedWebhook(Payment payment, PaymentDtos.WebhookRequest request) {
        Order order = payment.getOrder();

        // 환불 요청 상태가 아니면 경고
        if (order.getStatus() != Order.OrderStatus.REFUND_REQUESTED) {
            log.warn("Webhook REFUNDED received but order not in REFUND_REQUESTED: orderNo={}, status={}",
                    order.getOrderNo(), order.getStatus());
            return;
        }

        // 이미 REFUNDED 상태면 중복 처리 방지
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment already refunded, skipping webhook: orderNo={}, paymentKey={}",
                    order.getOrderNo(), request.paymentKey());
            return;
        }

        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        // 환불 테이블 상태 업데이트 (OLV-074)
        Refund refund = refundRepository.findByOrderIdAndStatus(
                order.getId(), Refund.RefundStatus.REQUESTED
        ).orElse(null);
        if (refund != null) {
            refund.approve(request.paymentKey(), OffsetDateTime.now());
            refundRepository.save(refund);
        }

        order.setStatus(Order.OrderStatus.REFUNDED.name());
        orderRepository.save(order);

        // 주문 상태 이력 기록
        OrderStatusHistory history = OrderStatusHistory.transition(
                order,
                Order.OrderStatus.REFUND_REQUESTED.name(),
                Order.OrderStatus.REFUNDED.name(),
                OrderStatusHistory.ChangedByKind.SYSTEM,
                null,
                "PG 환불 완료 (웹훅)"
        );
        orderStatusHistoryRepository.save(history);

        log.info("Webhook REFUNDED processed: orderNo={}, paymentKey={}",
                order.getOrderNo(), request.paymentKey());
    }
}
