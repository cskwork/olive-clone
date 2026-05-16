package com.olive.commerce.delivery;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.order.Order;
import com.olive.commerce.order.OrderRepository;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 배송 관련 사용자 API.
 */
@RestController
@RequestMapping("/api/me/orders")
public class DeliveryController {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryStatusHistoryRepository historyRepository;
    private final OrderRepository orderRepository;

    public DeliveryController(DeliveryRepository deliveryRepository,
                             DeliveryStatusHistoryRepository historyRepository,
                             OrderRepository orderRepository) {
        this.deliveryRepository = deliveryRepository;
        this.historyRepository = historyRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * 주문의 배송 목록을 조회합니다.
     *
     * @param orderNo 주문 번호
     * @param principal 인증된 사용자
     * @return 배송 목록
     */
    @GetMapping("/{orderNo}/deliveries")
    public ApiResponse<DeliveryDtos.DeliveryListResponse> getMyOrderDeliveries(
            @PathVariable String orderNo,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        // 주문 조회 및 소유권 검증
        Order order = orderRepository.findByOrderNo(orderNo)
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getMemberId().equals(principal.memberId())) {
            throw new BusinessException(ErrorCode.ORDER_NOT_OWNED);
        }

        // 배송 조회
        List<Delivery> deliveries = deliveryRepository.findByOrderId(order.getId());

        return ApiResponse.success(DeliveryDtos.DeliveryListResponse.of(deliveries));
    }

    /**
     * 배송 상세를 조회합니다 (상태 변경 이력 포함).
     *
     * @param id 배송 ID
     * @param principal 인증된 사용자
     * @return 배송 상세
     */
    @GetMapping("/deliveries/{id}")
    public ApiResponse<DeliveryDtos.DeliveryDetailResponse> getMyDeliveryDetail(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Delivery delivery = deliveryRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "Delivery not found"));

        // 소유권 검증
        Order order = orderRepository.findById(delivery.getOrderId())
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getMemberId().equals(principal.memberId())) {
            throw new BusinessException(ErrorCode.ORDER_NOT_OWNED);
        }

        // 상태 이력 조회
        List<DeliveryStatusHistory> histories = historyRepository
            .findByDeliveryIdOrderByCreatedAtDesc(id);

        return ApiResponse.success(DeliveryDtos.DeliveryDetailResponse.from(delivery, histories));
    }
}
