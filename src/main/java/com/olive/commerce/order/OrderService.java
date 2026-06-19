package com.olive.commerce.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.config.DomainProperties;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.inventory.InventoryService;
import com.olive.commerce.member.MemberAddress;
import com.olive.commerce.member.MemberAddressRepository;
import com.olive.commerce.member.MemberRepository;
import com.olive.commerce.payment.Payment;
import com.olive.commerce.payment.PaymentDtos;
import com.olive.commerce.payment.PaymentRepository;
import com.olive.commerce.payment.client.PgClient;
import com.olive.commerce.payment.client.dto.CancelRequest;
import com.olive.commerce.payment.client.dto.CancelResponse;
import com.olive.commerce.product.ProductOption;
import com.olive.commerce.product.ProductOptionRepository;
import com.olive.commerce.promotion.CouponService;
import com.olive.commerce.promotion.CouponDtos.ValidatedCoupon;
import com.olive.commerce.promotion.PointService;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import com.olive.commerce.common.util.PIIMasker;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 주문 서비스 (OLV-061).
 * <p>
 * 8단계 주문 생성 파이프라인 구현 (PRD §8.3).
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final Duration IDEMPOTENCY_CACHE_TTL = Duration.ofHours(24);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderPriceSummaryRepository orderPriceSummaryRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;

    private final MemberRepository memberRepository;
    private final MemberAddressRepository memberAddressRepository;
    private final ProductOptionRepository productOptionRepository;
    private final InventoryService inventoryService;
    private final CouponService couponService;
    private final PointService pointService;
    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;

    private final AuditLogger auditLogger;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DomainProperties domainProperties;
    private final OrderPricingCalculator orderPricingCalculator;

    /**
     * 주문 생성 (PRD §8.3 8단계 파이프라인).
     *
     * @param memberId 회원 ID
     * @param request 주문 생성 요청
     * @param idempotencyKey 멱등성 키 (Idempotency-Key 헤더)
     * @return 주문 생성 응답
     */
    @Transactional
    public OrderDtos.CreateOrderResponse createOrder(Long memberId, OrderDtos.CreateOrderRequest request, String idempotencyKey) {
        // Step 0: 멱등성 체크 (Idempotency-Key)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Order existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existingOrder != null) {
                log.info("Idempotent request: returning existing order {}", existingOrder.getOrderNo());
                return buildResponse(existingOrder, null);
            }
        }

        // Step 1: 회원 검증 (status = ACTIVE)
        validateMemberActive(memberId);

        // Step 2: 상품 판매 상태 검증
        List<OrderItemData> itemDataList = validateProductSaleStatus(request.items());

        // 주문 생성 후 ID 확보 (재고 예약에 실제 order_id 사용하기 위해)
        PriceCalculation calculation = orderPricingCalculator.calculate(itemDataList, null, request.usePointAmount());
        Order order = createOrderEntity(memberId, request.deliveryAddressId(), calculation, itemDataList);
        orderRepository.save(order);

        // DB 트리거로 생성된 orderNo 조회
        String generatedOrderNo = orderRepository.findOrderNoById(order.getId());
        order.setOrderNo(generatedOrderNo);

        Long orderId = order.getId();

        // Step 3: 재고 검증 + 선점 (실제 order_id 사용)
        List<InventoryService.ReserveItem> reserveItems = buildReserveItems(request.items());

        try {
            inventoryService.reserve(orderId, reserveItems, domainProperties.getReservationTtl());
        } catch (BusinessException e) {
            // Step 3 실패 시 예약이 생성되지 않으므로 주문도 롤백됨 (@Transactional)
            throw e;
        }

        try {
            // Step 4-8: 나머지 단계 실행 (이미 주문은 생성됨)
            return createOrderInternal(memberId, request, orderId, itemDataList, idempotencyKey, order, calculation);
        } catch (Exception e) {
            // Step 3 이후 실패 시 예약 해제
            try {
                inventoryService.release(orderId, "주문 생성 실패: " + e.getMessage());
            } catch (Exception releaseEx) {
                log.warn("Failed to release inventory for order {}: {}", orderId, releaseEx.getMessage());
            }
            throw e;
        }
    }

    /**
     * 주문 생성 내부 로직 (Step 4-8).
     * <p>
     * 이 메서드는 호출자의 트랜잭션 내에서 실행됩니다.
     * order와 calculation은 이미 생성되어 전달됩니다.
     */
    private OrderDtos.CreateOrderResponse createOrderInternal(
            Long memberId,
            OrderDtos.CreateOrderRequest request,
            Long orderId,
            List<OrderItemData> itemDataList,
            String idempotencyKey,
            Order order,
            PriceCalculation calculation
    ) {
        // Step 4: 쿠폰 검증
        ValidatedCoupon validatedCoupon = null;
        if (request.couponId() != null) {
            BigDecimal subtotal = orderPricingCalculator.subtotal(itemDataList);
            validatedCoupon = couponService.validate(memberId, request.couponId(), subtotal);
        }

        // Step 5: 포인트 검증
        if (request.usePointAmount() != null && request.usePointAmount().compareTo(BigDecimal.ZERO) > 0) {
            OffsetDateTime now = OffsetDateTime.now();
            BigDecimal spendable = pointService.spendableBalance(memberId, now);
            if (spendable.compareTo(request.usePointAmount()) < 0) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS,
                        "memberId=" + memberId + ", spendable=" + spendable + ", requested=" + request.usePointAmount());
            }
        }

        // Step 6: 최종 금액 계산 (쿠폰 적용하여 재계산)
        PriceCalculation finalCalculation = orderPricingCalculator.calculate(itemDataList, validatedCoupon, request.usePointAmount());

        // 가격 업데이트
        order.setPriceDetails(
                finalCalculation.subtotal(),
                finalCalculation.couponDiscount(),
                finalCalculation.pointDiscount(),
                finalCalculation.shippingFee(),
                finalCalculation.grandTotal()
        );
        orderRepository.save(order);

        // Order items
        for (OrderItemData itemData : itemDataList) {
            OrderItem item = OrderItem.create(
                    order,
                    itemData.productId(),
                    itemData.productOptionId(),
                    itemData.productName(),
                    itemData.optionName(),
                    itemData.unitPrice(),
                    itemData.quantity()
            );
            orderItemRepository.save(item);
        }

        // Price summary
        OrderPriceSummary priceSummary = OrderPriceSummary.create(order);
        priceSummary.setPriceDetails(
                finalCalculation.subtotal(),
                finalCalculation.couponDiscount(),
                finalCalculation.pointDiscount(),
                finalCalculation.shippingFee(),
                finalCalculation.grandTotal()
        );
        orderPriceSummaryRepository.save(priceSummary);

        // Status history (initial)
        OrderStatusHistory initialHistory = OrderStatusHistory.initial(order);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // 멱등성 키를 reason에 저장하여 나중에 조회 가능
            initialHistory = OrderStatusHistory.transition(
                    order,
                    null,
                    Order.OrderStatus.CREATED.name(),
                    OrderStatusHistory.ChangedByKind.SYSTEM,
                    null,
                    idempotencyKey
            );
        }
        orderStatusHistoryRepository.save(initialHistory);

        // 쿠폰 사용 상태로 변경
        if (validatedCoupon != null) {
            order.setUsedMemberCouponId(validatedCoupon.memberCouponId());
            couponService.markUsed(validatedCoupon.memberCouponId(), order.getId());
            orderRepository.save(order);
        }

        // 포인트 사용
        if (request.usePointAmount() != null && request.usePointAmount().compareTo(BigDecimal.ZERO) > 0) {
            pointService.use(memberId, request.usePointAmount(), order.getId());
        }

        // Step 8: 결제 요청 정보 생성 (Payment 레코드 생성)
        UUID idempotencyKeyUuid = idempotencyKey != null && !idempotencyKey.isBlank()
                ? UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8))
                : UUID.randomUUID();
        Payment payment = Payment.createRequest(
                order,
                Payment.PaymentMethod.CARD,
                finalCalculation.grandTotal(),
                idempotencyKeyUuid
        );
        paymentRepository.save(payment);

        // paymentKey 반환 (실제 PG 연동 전이므로 임시값 사용)
        Long paymentKey = payment.getId();

        // 상태를 PAYMENT_PENDING으로 전이
        order.toPaymentPending();
        orderRepository.save(order);

        // Status history: CREATED → PAYMENT_PENDING
        OrderStatusHistory pendingHistory = OrderStatusHistory.transition(
                order,
                Order.OrderStatus.CREATED.name(),
                Order.OrderStatus.PAYMENT_PENDING.name(),
                OrderStatusHistory.ChangedByKind.SYSTEM,
                null,
                "결제 대기"
        );
        orderStatusHistoryRepository.save(pendingHistory);

        // Outbox 이벤트 발행 (같은 트랜잭션)
        List<OrderCreatedEvent.OrderItemData> eventItems = itemDataList.stream()
                .map(item -> new OrderCreatedEvent.OrderItemData(
                        item.productId(),
                        item.productOptionId(),
                        item.quantity(),
                        item.unitPrice()
                ))
                .toList();

        try {
            String payloadJson = objectMapper.writeValueAsString(
                    Map.of(
                            "orderId", order.getId(),
                            "orderNo", order.getOrderNo(),
                            "memberId", memberId,
                            "finalPaymentAmount", finalCalculation.grandTotal(),
                            "items", eventItems
                    )
            );
            OutboxEvent outboxEvent = OutboxEvent.create(
                    "ORDER",
                    order.getId(),
                    "ORDER_CREATED",
                    payloadJson
            );
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.warn("Failed to create outbox event for order {}: {}", order.getId(), e.getMessage());
        }

        // Audit log
        auditLogger.log("ORDER_CREATED", Map.of(
                "orderId", order.getId(),
                "orderNo", order.getOrderNo(),
                "memberId", memberId,
                "amount", finalCalculation.grandTotal()
        ));

        // 멱등성 캐시 저장 (Redis)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String cacheKey = "idempotency:order:" + idempotencyKey;
            redisTemplate.opsForValue().set(cacheKey, order.getOrderNo(), IDEMPOTENCY_CACHE_TTL);
        }

        return buildResponse(order, paymentKey);
    }

    /**
     * 회원 상태 검증 (Step 1).
     */
    private void validateMemberActive(Long memberId) {
        memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND, "memberId=" + memberId));

        // 추가적인 상태 검증이 필요하면 여기에 구현
        // 현재는 Member 엔티티에 status 필드가 없으므로 생략
    }

    /**
     * 배송지 소유권 검증.
     */
    private void validateDeliveryAddressOwnership(Long memberId, Long addressId) {
        MemberAddress address = memberAddressRepository.findById(addressId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, "addressId=" + addressId));

        if (!address.getMemberId().equals(memberId)) {
            throw new BusinessException(ErrorCode.ADDRESS_NOT_OWNED,
                    "addressId=" + addressId + " does not belong to memberId=" + memberId);
        }
    }

    /**
     * 상품 판매 상태 검증 (Step 2).
     * <p>
     * 모든 productOptionId가 ON_SALE 상태의 옵션을 참조하는지 확인.
     */
    private List<OrderItemData> validateProductSaleStatus(List<OrderDtos.CreateOrderRequest.OrderItemRequest> items) {
        List<OrderItemData> result = new ArrayList<>();

        for (var item : items) {
            ProductOption option = productOptionRepository.findByIdWithProduct(item.productOptionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                            "productOptionId=" + item.productOptionId()));

            // 상품 판매 상태 검증
            if (option.getProduct() == null || option.getProduct().getStatus() != com.olive.commerce.product.Product.ProductStatus.ON_SALE) {
                throw new BusinessException(ErrorCode.PRODUCT_SOLD_OUT,
                        "Product is not on sale: productOptionId=" + item.productOptionId());
            }

            // 옵션 판매 상태 검증
            if (option.getStatus() != ProductOption.OptionStatus.ON_SALE) {
                throw new BusinessException(ErrorCode.PRODUCT_SOLD_OUT,
                        "Option is not on sale: productOptionId=" + item.productOptionId());
            }

            // 판매 가격: 옵션별 option_price 사용
            // (상품 수준 sale_price는 옵션 가격과 독립적인 할인가이므로 무시)
            BigDecimal unitPrice = option.getOptionPrice();

            result.add(new OrderItemData(
                    option.getProduct().getId(),
                    option.getId(),
                    item.quantity(),
                    option.getProduct().getName(),
                    option.getOptionName(),
                    unitPrice
            ));
        }

        return result;
    }

    /**
     * 재고 예약 아이템 목록 생성.
     */
    private List<InventoryService.ReserveItem> buildReserveItems(List<OrderDtos.CreateOrderRequest.OrderItemRequest> items) {
        return items.stream()
                .map(item -> new InventoryService.ReserveItem(item.productOptionId(), item.quantity()))
                .toList();
    }

    /**
     * 주문 엔티티 생성.
     */
    private Order createOrderEntity(
            Long memberId,
            Long deliveryAddressId,
            PriceCalculation calculation,
            List<OrderItemData> itemDataList
    ) {
        // 배송지 소유권 검증
        validateDeliveryAddressOwnership(memberId, deliveryAddressId);

        Order order = Order.create(memberId, deliveryAddressId);
        order.setPriceDetails(
                calculation.subtotal(),
                calculation.couponDiscount(),
                calculation.pointDiscount(),
                calculation.shippingFee(),
                calculation.grandTotal()
        );
        return order;
    }

    /**
     * 응답 빌더.
     */
    private OrderDtos.CreateOrderResponse buildResponse(Order order, Long paymentKey) {
        if (paymentKey == null) {
            paymentKey = order.getId();
        }

        return new OrderDtos.CreateOrderResponse(
                order.getOrderNo(),
                paymentKey,
                order.getFinalPaymentAmount(),
                null // PG checkout payload: PG사별 구현 필요 시 추가
        );
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
     * 주문 번호로 주문 조회 (컨트롤러용).
     */
    @Transactional(readOnly = true)
    public Order findByOrderNo(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "orderNo=" + orderNo));
    }

    /**
     * ID로 주문 조회 (관리자 컨트롤러용).
     */
    @Transactional(readOnly = true)
    public Order findById(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "orderId=" + orderId));
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

        CancelResponse pgResponse = pgClient.cancelPayment(new CancelRequest(
                paymentKey,
                order.getFinalPaymentAmount(),
                reason
        ));

        payment.setStatus(Payment.PaymentStatus.CANCELED);
        payment.setFailedReason(reason);
        paymentRepository.save(payment);

        log.info("PG cancel completed for order {}: paymentKey={}, pgStatus={}",
                orderId, paymentKey, pgResponse.status());
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
    @Transactional(readOnly = true)
    public Page<OrderDtos.MyOrderListResponse> getMyOrders(Long memberId, String status, Pageable pageable) {
        Page<Order> orders = orderRepository.findByMemberIdAndStatus(memberId, status, pageable);

        return orders.map(order -> new OrderDtos.MyOrderListResponse(
                order.getId(),
                order.getOrderNo(),
                order.getStatus().name(),
                order.getTotalProductAmount(),
                order.getFinalPaymentAmount(),
                order.getCreatedAt()
        ));
    }

    /**
     * 회원 주문 상세 조회 (OLV-063).
     *
     * @param memberId 회원 ID
     * @param orderNo  주문 번호
     * @return 주문 상세
     */
    @Transactional(readOnly = true)
    public OrderDtos.MyOrderDetailResponse getMyOrderDetail(Long memberId, String orderNo) {
        Order order = findAndValidateOwnership(memberId, orderNo);

        // 배송지 정보 로드
        Order orderWithDelivery = orderRepository.findByOrderNoWithDeliveryAddress(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "orderNo=" + orderNo));

        // 주문 상품 로드
        Order orderWithItems = orderRepository.findByIdWithItemsAndDelivery(order.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "orderId=" + order.getId()));

        // 상태 변경 이력
        List<OrderStatusHistory> histories = orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtDesc(order.getId());

        return buildMyOrderDetailResponse(orderWithItems, histories);
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
    @Transactional(readOnly = true)
    public Page<OrderDtos.AdminOrderListResponse> getAdminOrders(
            String status,
            Long memberId,
            OffsetDateTime from,
            OffsetDateTime to,
            Pageable pageable
    ) {
        Page<Order> orders = orderRepository.findByFilters(status, memberId, from, to, pageable);

        return orders.map(order -> {
            // 배송지 정보 로드
            Order orderWithDelivery = orderRepository.findByIdWithDeliveryAddress(order.getId())
                    .orElse(order);

            MemberAddress delivery = orderWithDelivery.getDeliveryAddress();
            OrderDtos.AdminOrderListResponse.DeliveryInfo deliveryInfo;
            if (delivery != null) {
                // PII 마스킹
                deliveryInfo = new OrderDtos.AdminOrderListResponse.DeliveryInfo(
                        PIIMasker.maskName(delivery.getRecipientName()),
                        PIIMasker.maskPhone(delivery.getPhone()),
                        PIIMasker.maskAddress(delivery.getAddressMain() + " " + delivery.getAddressDetail())
                );
            } else {
                deliveryInfo = new OrderDtos.AdminOrderListResponse.DeliveryInfo(null, null, null);
            }

            return new OrderDtos.AdminOrderListResponse(
                    order.getId(),
                    order.getOrderNo(),
                    order.getMemberId(),
                    order.getStatus().name(),
                    order.getTotalProductAmount(),
                    order.getFinalPaymentAmount(),
                    deliveryInfo,
                    order.getCreatedAt()
            );
        });
    }

    /**
     * 관리자 주문 상세 조회 (OLV-063).
     * <p>
     * 모든 정보 포함 (PII 마스킹 없음).
     *
     * @param orderId 주문 ID
     * @return 주문 상세
     */
    @Transactional(readOnly = true)
    public OrderDtos.AdminOrderDetailResponse getAdminOrderDetail(Long orderId) {
        Order orderWithItems = orderRepository.findByIdWithItemsAndDelivery(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "orderId=" + orderId));

        // 배송지 정보
        Order orderWithDelivery = orderRepository.findByIdWithDeliveryAddress(orderId)
                .orElse(orderWithItems);

        MemberAddress delivery = orderWithDelivery.getDeliveryAddress();
        OrderDtos.AdminOrderDetailResponse.DeliveryInfo deliveryInfo;
        if (delivery != null) {
            deliveryInfo = new OrderDtos.AdminOrderDetailResponse.DeliveryInfo(
                    delivery.getRecipientName(),
                    delivery.getPhone(),
                    delivery.getZipcode(),
                    delivery.getAddressMain(),
                    delivery.getAddressDetail()
            );
        } else {
            deliveryInfo = new OrderDtos.AdminOrderDetailResponse.DeliveryInfo(null, null, null, null, null);
        }

        // 상태 변경 이력
        List<OrderStatusHistory> histories = orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtDesc(orderId);

        List<OrderDtos.AdminOrderDetailResponse.StatusHistoryResponse> historyResponses = histories.stream()
                .map(h -> new OrderDtos.AdminOrderDetailResponse.StatusHistoryResponse(
                        h.getFromStatus(),
                        h.getToStatus(),
                        h.getReason(),
                        h.getChangedByKind().name(),
                        h.getChangedById(),
                        h.getCreatedAt()
                ))
                .toList();

        List<OrderDtos.OrderItemResponse> itemResponses = orderWithItems.getItems().stream()
                .map(item -> new OrderDtos.OrderItemResponse(
                        item.getId(),
                        item.getProductName(),
                        item.getOptionName(),
                        item.getUnitPrice(),
                        item.getQuantity(),
                        item.getTotalAmount()
                ))
                .toList();

        return new OrderDtos.AdminOrderDetailResponse(
                orderWithItems.getId(),
                orderWithItems.getOrderNo(),
                orderWithItems.getMemberId(),
                orderWithItems.getStatus().name(),
                orderWithItems.getTotalProductAmount(),
                orderWithItems.getDiscountAmount(),
                orderWithItems.getPointUsedAmount(),
                orderWithItems.getDeliveryFee(),
                orderWithItems.getFinalPaymentAmount(),
                orderWithItems.getUsedMemberCouponId(),
                itemResponses,
                deliveryInfo,
                historyResponses,
                orderWithItems.getCreatedAt(),
                orderWithItems.getUpdatedAt()
        );
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
    @Transactional
    public OrderDtos.StatusUpdateResponse updateOrderStatus(
            Long orderId,
            OrderDtos.StatusUpdateRequest request,
            Long adminId
    ) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "orderId=" + orderId));

        Order.OrderStatus fromStatus = order.getStatus();
        Order.OrderStatus toStatus;

        try {
            toStatus = Order.OrderStatus.valueOf(request.toStatus());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Invalid status: " + request.toStatus());
        }

        // 상태 전이 검증
        if (!isValidAdminTransition(fromStatus, toStatus)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    String.format("Cannot transition from %s to %s", fromStatus, toStatus));
        }

        // 상태 변경 (Order 엔티티의 검증 로직 재사용)
        if (toStatus == Order.OrderStatus.PREPARING) {
            order.toPaid(); // PAID → PREPARING는 toPaid 후 수동 전이
            order.setStatusDirectly(Order.OrderStatus.PREPARING);
        } else if (toStatus == Order.OrderStatus.SHIPPING) {
            order.toPreparing(); // PREPARING 먼저 거쳐야 함
            order.setStatusDirectly(Order.OrderStatus.SHIPPING);
        } else if (toStatus == Order.OrderStatus.DELIVERED) {
            order.toPreparing();
            order.setStatusDirectly(Order.OrderStatus.DELIVERED);
        } else if (toStatus == Order.OrderStatus.REFUND_REQUESTED) {
            order.toDelivered();
            order.setStatusDirectly(Order.OrderStatus.REFUND_REQUESTED);
        } else if (toStatus == Order.OrderStatus.REFUNDED) {
            // REFUND_REQUESTED → REFUNDED
            order.setStatusDirectly(Order.OrderStatus.REFUNDED);
        } else {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    String.format("Admin cannot transition to %s", toStatus));
        }

        orderRepository.save(order);

        // 상태 변경 이력 기록
        OrderStatusHistory history = OrderStatusHistory.transition(
                order,
                fromStatus.name(),
                toStatus.name(),
                OrderStatusHistory.ChangedByKind.ADMIN,
                adminId,
                request.reason()
        );
        orderStatusHistoryRepository.save(history);

        // 감사 로그
        auditLogger.log("ADMIN_STATUS_UPDATE", Map.of(
                "adminId", adminId != null ? adminId : "UNKNOWN",
                "orderId", orderId,
                "orderNo", order.getOrderNo(),
                "fromStatus", fromStatus.name(),
                "toStatus", toStatus.name(),
                "reason", request.reason()
        ));

        return new OrderDtos.StatusUpdateResponse(
                orderId,
                order.getOrderNo(),
                fromStatus.name(),
                toStatus.name()
        );
    }

    /**
     * 관리자가 수행할 수 있는 상태 전인지 검증.
     */
    private boolean isValidAdminTransition(Order.OrderStatus from, Order.OrderStatus to) {
        // 허용된 전이:
        // PAID → PREPARING
        // PREPARING → SHIPPING
        // SHIPPING → DELIVERED
        // DELIVERED → REFUND_REQUESTED
        // REFUND_REQUESTED → REFUNDED
        return switch (from) {
            case PAID -> to == Order.OrderStatus.PREPARING;
            case PREPARING -> to == Order.OrderStatus.SHIPPING;
            case SHIPPING -> to == Order.OrderStatus.DELIVERED;
            case DELIVERED -> to == Order.OrderStatus.REFUND_REQUESTED;
            case REFUND_REQUESTED -> to == Order.OrderStatus.REFUNDED;
            default -> false;
        };
    }

    /**
     * 회원 주문 상세 응답 빌더.
     */
    private OrderDtos.MyOrderDetailResponse buildMyOrderDetailResponse(
            Order order,
            List<OrderStatusHistory> histories
    ) {
        MemberAddress delivery = order.getDeliveryAddress();
        OrderDtos.MyOrderDetailResponse.DeliveryInfo deliveryInfo;
        if (delivery != null) {
            deliveryInfo = new OrderDtos.MyOrderDetailResponse.DeliveryInfo(
                    delivery.getRecipientName(),
                    delivery.getPhone(),
                    delivery.getZipcode(),
                    delivery.getAddressMain(),
                    delivery.getAddressDetail()
            );
        } else {
            deliveryInfo = new OrderDtos.MyOrderDetailResponse.DeliveryInfo(null, null, null, null, null);
        }

        List<OrderDtos.MyOrderDetailResponse.StatusHistoryResponse> historyResponses = histories.stream()
                .map(h -> new OrderDtos.MyOrderDetailResponse.StatusHistoryResponse(
                        h.getFromStatus(),
                        h.getToStatus(),
                        h.getReason(),
                        h.getChangedByKind().name(),
                        h.getCreatedAt()
                ))
                .toList();

        List<OrderDtos.OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> new OrderDtos.OrderItemResponse(
                        item.getId(),
                        item.getProductName(),
                        item.getOptionName(),
                        item.getUnitPrice(),
                        item.getQuantity(),
                        item.getTotalAmount()
                ))
                .toList();

        return new OrderDtos.MyOrderDetailResponse(
                order.getId(),
                order.getOrderNo(),
                order.getStatus().name(),
                order.getTotalProductAmount(),
                order.getDiscountAmount(),
                order.getPointUsedAmount(),
                order.getDeliveryFee(),
                order.getFinalPaymentAmount(),
                itemResponses,
                deliveryInfo,
                historyResponses,
                order.getCreatedAt()
        );
    }
}
