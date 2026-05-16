package com.olive.commerce.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.inventory.InventoryService;
import com.olive.commerce.order.Order;
import com.olive.commerce.order.OrderItem;
import com.olive.commerce.order.OrderRepository;
import com.olive.commerce.order.OrderStatusHistory;
import com.olive.commerce.order.OrderStatusHistoryRepository;
import com.olive.commerce.payment.Refund.RefundStatus;
import com.olive.commerce.payment.Payment.PaymentStatus;
import com.olive.commerce.payment.client.PgClient;
import com.olive.commerce.payment.client.dto.RefundRequest;
import com.olive.commerce.payment.client.dto.RefundResponse;
import com.olive.commerce.promotion.PointService;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 환불 서비스 (OLV-074).
 * <p>
 * 사용자 환불 요청, 관리자 승인/거절, PG 환불 호출을 처리합니다.
 */
@Service
@RequiredArgsConstructor
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PgClient pgClient;
    private final InventoryService inventoryService;
    private final PointService pointService;
    private final AuditLogger auditLogger;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 환불 요청 생성 (사용자).
     *
     * @param memberId 회원 ID
     * @param orderNo  주문 번호
     * @param request  환불 요청
     * @return 생성된 환불
     */
    @Transactional
    public Refund requestRefund(Long memberId, String orderNo, RefundDtos.RefundRequestDto request) {
        // Step 1: 주문 조회 및 소유권 검증
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                        "orderNo=" + orderNo));

        if (!order.getMemberId().equals(memberId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_OWNED, "Not your order");
        }

        // Step 2: 주문 상태 검증 (DELIVERED만 허용, 관리자 승인 예외는 별도 API)
        if (order.getStatus() != Order.OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Order must be DELIVERED to request refund: " + order.getStatus());
        }

        // Step 3: Payment 조회
        Payment payment = paymentRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "No payment found for order: " + order.getId()));

        if (payment.getStatus() != PaymentStatus.APPROVED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Payment must be APPROVED to request refund: " + payment.getStatus());
        }

        // Step 4: 기존 환불 요청 확인
        if (refundRepository.findByOrderIdAndStatus(order.getId(), RefundStatus.REQUESTED).isPresent()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Refund already requested for this order");
        }

        // Step 5: 환불 가능 금액 계산
        BigDecimal totalRefunded = refundRepository.sumApprovedAmountByPaymentId(payment.getId());
        BigDecimal maxRefundable = order.getFinalPaymentAmount().subtract(totalRefunded);

        // 간단 구현: 전체 환불만 (request.items는 무시)
        // TODO: 부분 환불 시 items의 수량 비례 계산 필요
        BigDecimal refundAmount = maxRefundable;

        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "No refundable amount remaining");
        }

        // Step 6: 환불 생성
        Refund refund = Refund.request(payment, order, refundAmount, request.reason());
        refundRepository.save(refund);

        // Step 7: 주문 상태 변경
        order.setStatusDirectly(Order.OrderStatus.REFUND_REQUESTED);
        orderRepository.save(order);

        // Step 8: 주문 상태 이력 기록
        OrderStatusHistory history = OrderStatusHistory.transition(
                order,
                Order.OrderStatus.DELIVERED.name(),
                Order.OrderStatus.REFUND_REQUESTED.name(),
                OrderStatusHistory.ChangedByKind.USER,
                memberId,
                "환불 요청: " + request.reason()
        );
        orderStatusHistoryRepository.save(history);

        Map<String, Object> auditData = new java.util.HashMap<>();
        auditData.put("refundId", refund.getId());
        auditData.put("orderId", order.getId());
        auditData.put("orderNo", orderNo);
        auditData.put("amount", refundAmount);
        auditData.put("reason", request.reason());
        auditLogger.log("REFUND_REQUESTED", auditData);

        log.info("Refund requested: refundId={}, orderNo={}, amount={}",
                refund.getId(), orderNo, refundAmount);

        return refund;
    }

    /**
     * 환불 승인 (관리자).
     *
     * @param refundId 환불 ID
     * @param adminId  관리자 ID
     * @return 승인 결과
     */
    @Transactional
    public RefundDtos.ApproveResponse approveRefund(Long refundId, Long adminId) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND,
                        "refundId=" + refundId));

        // 멱등성: 이미 승인된 환불은 no-op
        if (refund.getStatus() == RefundStatus.APPROVED) {
            log.info("Refund already approved: refundId={}", refundId);
            return new RefundDtos.ApproveResponse(
                    refund.getId(),
                    refund.getStatus(),
                    refund.getPgRefundKey(),
                    "Already approved"
            );
        }

        if (refund.getStatus() != RefundStatus.REQUESTED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Refund is not in REQUESTED status: " + refund.getStatus());
        }

        Order order = refund.getOrder();
        Payment payment = refund.getPayment();

        // Step 1: PG 환불 호출
        String pgRefundKey;
        try {
            RefundResponse pgResponse = pgClient.refund(new RefundRequest(
                    payment.getPaymentKey(),
                    refund.getAmount(),
                    refund.getReason() != null ? refund.getReason() : "관리자 환불 승인"
            ));
            pgRefundKey = pgResponse.pgRefundKey();
        } catch (Exception e) {
            log.error("PG refund failed for refundId={}: {}", refundId, e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "PG refund failed: " + e.getMessage());
        }

        OffsetDateTime now = OffsetDateTime.now();

        // Step 2: 환불 승인 처리
        refund.approve(pgRefundKey, now);
        refundRepository.save(refund);

        // Step 3: Payment 상태 변경 (REFUNDED)
        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        // Step 4: 주문 상태 변경 (REFUNDED)
        order.setStatusDirectly(Order.OrderStatus.REFUNDED);
        orderRepository.save(order);

        // Step 5: 주문 상태 이력 기록
        OrderStatusHistory history = OrderStatusHistory.transition(
                order,
                Order.OrderStatus.REFUND_REQUESTED.name(),
                Order.OrderStatus.REFUNDED.name(),
                OrderStatusHistory.ChangedByKind.ADMIN,
                adminId,
                "환불 승인"
        );
        orderStatusHistoryRepository.save(history);

        // Step 6: 재고 복구
        for (OrderItem item : order.getItems()) {
            inventoryService.adjust(
                    item.getProductOptionId(),
                    item.getQuantity(),
                    "환불 복구: 주문 " + order.getOrderNo(),
                    adminId
            );
        }

        // Step 7: 포인트 복구
        if (order.getPointUsedAmount().compareTo(BigDecimal.ZERO) > 0) {
            pointService.cancel(order.getMemberId(), order.getId());
        }

        // Step 8: Outbox 이벤트 생성
        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("refundId", refund.getId());
            payload.put("orderId", order.getId());
            payload.put("orderNo", order.getOrderNo());
            payload.put("paymentId", payment.getId());
            payload.put("amount", refund.getAmount());
            payload.put("pgRefundKey", pgRefundKey);
            String payloadJson = objectMapper.writeValueAsString(payload);
            OutboxEvent outboxEvent = OutboxEvent.create(
                    "PAYMENT",
                    refund.getId(),
                    "ORDER_REFUNDED",
                    payloadJson
            );
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to create outbox event for refund {}: {}", refund.getId(), e.getMessage());
        }

        // Audit log
        Map<String, Object> auditData = new java.util.HashMap<>();
        auditData.put("refundId", refund.getId());
        auditData.put("orderId", order.getId());
        auditData.put("orderNo", order.getOrderNo());
        auditData.put("amount", refund.getAmount());
        auditData.put("adminId", adminId);
        auditLogger.log("REFUND_APPROVED", auditData);

        // Spring 이벤트 발행
        eventPublisher.publishEvent(new OrderRefundedEvent(
                this,
                refund.getId(),
                order.getId(),
                order.getOrderNo(),
                payment.getId(),
                refund.getAmount()
        ));

        log.info("Refund approved: refundId={}, orderNo={}, amount={}, pgRefundKey={}",
                refund.getId(), order.getOrderNo(), refund.getAmount(), pgRefundKey);

        return new RefundDtos.ApproveResponse(
                refund.getId(),
                refund.getStatus(),
                pgRefundKey,
                "Refund approved successfully"
        );
    }

    /**
     * 환불 거절 (관리자).
     *
     * @param refundId 환불 ID
     * @param request  거절 요청
     * @param adminId  관리자 ID
     * @return 거절된 환불
     */
    @Transactional
    public Refund rejectRefund(Long refundId, RefundDtos.RejectRequest request, Long adminId) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND,
                        "refundId=" + refundId));

        // 멱등성: 이미 거절된 환불은 no-op
        if (refund.getStatus() == RefundStatus.FAILED) {
            log.info("Refund already rejected: refundId={}", refundId);
            return refund;
        }

        if (refund.getStatus() != RefundStatus.REQUESTED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Refund is not in REQUESTED status: " + refund.getStatus());
        }

        Order order = refund.getOrder();

        // 환불 거절 처리
        refund.reject(request.reason());
        refundRepository.save(refund);

        // 주문 상태 복구 (DELIVERED로)
        order.setStatusDirectly(Order.OrderStatus.DELIVERED);
        orderRepository.save(order);

        // 주문 상태 이력 기록
        OrderStatusHistory history = OrderStatusHistory.transition(
                order,
                Order.OrderStatus.REFUND_REQUESTED.name(),
                Order.OrderStatus.DELIVERED.name(),
                OrderStatusHistory.ChangedByKind.ADMIN,
                adminId,
                "환불 거절: " + request.reason()
        );
        orderStatusHistoryRepository.save(history);

        Map<String, Object> auditData = new java.util.HashMap<>();
        auditData.put("refundId", refund.getId());
        auditData.put("orderId", order.getId());
        auditData.put("orderNo", order.getOrderNo());
        auditData.put("reason", request.reason());
        auditData.put("adminId", adminId);
        auditLogger.log("REFUND_REJECTED", auditData);

        log.info("Refund rejected: refundId={}, orderNo={}, reason={}",
                refund.getId(), order.getOrderNo(), request.reason());

        return refund;
    }

    /**
     * 환불 목록 조회 (관리자).
     *
     * @param status   환불 상태 (null이면 전체)
     * @param pageable 페이징 정보
     * @return 환불 목록
     */
    @Transactional(readOnly = true)
    public Page<RefundDtos.AdminResponse> listRefunds(RefundStatus status, Pageable pageable) {
        if (status != null) {
            List<Refund> refunds = refundRepository.findByStatus(status);
            List<RefundDtos.AdminResponse> responses = refunds.stream()
                    .map(RefundDtos.AdminResponse::from)
                    .toList();
            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), responses.size());
            List<RefundDtos.AdminResponse> paged = start < responses.size()
                    ? responses.subList(start, end)
                    : List.of();
            return new org.springframework.data.domain.PageImpl<>(paged, pageable, responses.size());
        }
        return refundRepository.findAll(pageable)
                .map(RefundDtos.AdminResponse::from);
    }

    /**
     * 환불 상세 조회 (관리자).
     *
     * @param refundId 환불 ID
     * @return 환불 상세
     */
    @Transactional(readOnly = true)
    public RefundDtos.AdminResponse getRefundDetail(Long refundId) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND,
                        "refundId=" + refundId));
        return RefundDtos.AdminResponse.from(refund);
    }
}
