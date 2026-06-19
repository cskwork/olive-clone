package com.olive.commerce.order;

import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.util.PIIMasker;
import com.olive.commerce.member.MemberAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 주문 조회 서비스 (OLV-063).
 * <p>
 * 회원/관리자 주문 목록·상세 조회와 관리자 상태 전이를 담당한다.
 * OrderService 파사드에서 분리한 조회 협력자.
 */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final AuditLogger auditLogger;

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
