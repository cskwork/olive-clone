package com.olive.commerce.order;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;

/**
 * 주문 서비스 파사드 (OLV-061/062/063).
 * <p>
 * 응집도가 낮았던 OrderService를 세 협력자로 분리하고, 기존 공개 API를 그대로
 * 위임하는 얇은 파사드로 남긴다. 컨트롤러와 테스트는 변경 없이 OrderService를 호출한다.
 * <ul>
 *     <li>{@link OrderCreationService} — 8단계 주문 생성 파이프라인 (PRD §8.3).</li>
 *     <li>{@link OrderCancellationService} — 사용자/관리자 취소 + PG 취소/재고·쿠폰·포인트 보상.</li>
 *     <li>{@link OrderQueryService} — 목록/상세 조회 및 관리자 상태 전이.</li>
 * </ul>
 * 트랜잭션 경계는 각 협력자의 {@code @Transactional}에 그대로 보존된다 (위임 호출 = 협력자에서 트랜잭션 시작).
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderCreationService orderCreationService;
    private final OrderCancellationService orderCancellationService;
    private final OrderQueryService orderQueryService;

    /**
     * 주문 생성 (PRD §8.3 8단계 파이프라인).
     *
     * @param memberId 회원 ID
     * @param request 주문 생성 요청
     * @param idempotencyKey 멱등성 키 (Idempotency-Key 헤더)
     * @return 주문 생성 응답
     */
    public OrderDtos.CreateOrderResponse createOrder(Long memberId, OrderDtos.CreateOrderRequest request, String idempotencyKey) {
        return orderCreationService.createOrder(memberId, request, idempotencyKey);
    }

    /**
     * OrderCreatedEvent 발행 리스너 (트랜잭션 커밋 후).
     */
    @TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("Order created event published: orderId={}, orderNo={}", event.orderId(), event.orderNo());
        // 실제 이벤트 처리는 outbox 워커가 담당
    }

    // ========== Order Cancel (OLV-062) ==========

    /**
     * 사용자 주문 취소 (OLV-062).
     * <p>
     * PAYMENT_PENDING/PAID/PREPARING 상태에서만 취소 가능합니다.
     *
     * @param memberId 회원 ID
     * @param orderNo  주문 번호
     * @param reason   취소 사유 (선택)
     */
    public void cancelUserOrder(Long memberId, String orderNo, String reason) {
        orderCancellationService.cancelUserOrder(memberId, orderNo, reason);
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
    public void cancelAdminOrder(Long orderId, String reason, Long adminId) {
        orderCancellationService.cancelAdminOrder(orderId, reason, adminId);
    }

    /**
     * 주문 번호로 주문 조회 (컨트롤러용).
     */
    public Order findByOrderNo(String orderNo) {
        return orderQueryService.findByOrderNo(orderNo);
    }

    /**
     * ID로 주문 조회 (관리자 컨트롤러용).
     */
    public Order findById(Long orderId) {
        return orderQueryService.findById(orderId);
    }

    // ========== Order List/Detail (OLV-063) ==========

    /**
     * 회원 주문 목록 조회 (OLV-063).
     *
     * @param memberId 회원 ID
     * @param status   주문 상태 필터 (null이면 전체)
     * @param pageable 페이지네이션
     * @return 주문 목록 페이지
     */
    public Page<OrderDtos.MyOrderListResponse> getMyOrders(Long memberId, String status, Pageable pageable) {
        return orderQueryService.getMyOrders(memberId, status, pageable);
    }

    /**
     * 회원 주문 상세 조회 (OLV-063).
     *
     * @param memberId 회원 ID
     * @param orderNo  주문 번호
     * @return 주문 상세
     */
    public OrderDtos.MyOrderDetailResponse getMyOrderDetail(Long memberId, String orderNo) {
        return orderQueryService.getMyOrderDetail(memberId, orderNo);
    }

    /**
     * 관리자 주문 목록 조회 (OLV-063).
     * <p>
     * PII 마스킹 포함.
     *
     * @param status   주문 상태 필터
     * @param memberId 회원 ID 필터
     * @param from     시작일시
     * @param to       종료일시
     * @param pageable 페이지네이션
     * @return 주문 목록 페이지 (PII 마스킹됨)
     */
    public Page<OrderDtos.AdminOrderListResponse> getAdminOrders(
            String status,
            Long memberId,
            OffsetDateTime from,
            OffsetDateTime to,
            Pageable pageable
    ) {
        return orderQueryService.getAdminOrders(status, memberId, from, to, pageable);
    }

    /**
     * 관리자 주문 상세 조회 (OLV-063).
     * <p>
     * 모든 정보 포함 (PII 마스킹 없음).
     *
     * @param orderId 주문 ID
     * @return 주문 상세
     */
    public OrderDtos.AdminOrderDetailResponse getAdminOrderDetail(Long orderId) {
        return orderQueryService.getAdminOrderDetail(orderId);
    }

    /**
     * 관리자 주문 상태 변경 (OLV-063).
     * <p>
     * 허용된 전이만 가능합니다.
     *
     * @param orderId 주문 ID
     * @param request 상태 변경 요청
     * @param adminId  관리자 ID
     * @return 상태 변경 응답
     */
    public OrderDtos.StatusUpdateResponse updateOrderStatus(
            Long orderId,
            OrderDtos.StatusUpdateRequest request,
            Long adminId
    ) {
        return orderQueryService.updateOrderStatus(orderId, request, adminId);
    }
}
