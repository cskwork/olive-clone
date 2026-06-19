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
import com.olive.commerce.payment.PaymentRepository;
import com.olive.commerce.product.ProductOption;
import com.olive.commerce.product.ProductOptionRepository;
import com.olive.commerce.promotion.CouponService;
import com.olive.commerce.promotion.CouponDtos.ValidatedCoupon;
import com.olive.commerce.promotion.PointService;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 주문 생성 서비스 (OLV-061).
 * <p>
 * 8단계 주문 생성 파이프라인 구현 (PRD §8.3).
 * OrderService 파사드에서 분리한 주문 생성 협력자.
 */
@Service
@RequiredArgsConstructor
public class OrderCreationService {

    private static final Logger log = LoggerFactory.getLogger(OrderCreationService.class);

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

    private final AuditLogger auditLogger;
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
}
