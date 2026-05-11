package com.olive.commerce.order;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 컨트롤러 (OLV-061, OLV-062).
 * <p>
 * 주문 생성 및 취소 엔드포인트를 제공합니다.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 주문 생성 (PRD §8.3).
     * <p>
     * 8단계 파이프라인을 통해 주문을 생성하고 결제 정보를 반환합니다.
     *
     * @param request 주문 생성 요청
     * @param idempotencyKey 멱등성 키 (Idempotency-Key 헤더)
     * @return 주문 생성 응답
     */
    @PostMapping
    public ResponseEntity<ApiResponse<OrderDtos.CreateOrderResponse>> createOrder(
            @Valid @RequestBody OrderDtos.CreateOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        // 인증된 회원 ID
        Long memberId = principal.memberId();

        OrderDtos.CreateOrderResponse response = orderService.createOrder(memberId, request, idempotencyKey);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    /**
     * 주문 취소 (OLV-062).
     * <p>
     * PAYMENT_PENDING/PAID/PREPARING 상태의 주문을 취소합니다.
     * 취소 시 재고 해제, 쿠폰 복구, 포인트 복구가 수행됩니다.
     *
     * @param orderNo 주문 번호
     * @param request 취소 요청 (사유 선택)
     * @param principal 인증된 회원
     * @return 취소 응답
     */
    @PostMapping("/{orderNo}/cancel")
    public ResponseEntity<ApiResponse<OrderDtos.CancelOrderResponse>> cancelOrder(
            @PathVariable String orderNo,
            @RequestBody(required = false) OrderDtos.CancelOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Long memberId = principal.memberId();
        String reason = request != null ? request.reason() : null;

        orderService.cancelUserOrder(memberId, orderNo, reason);

        // 취소된 주문 정보를 조회하여 응답 생성
        Order order = orderService.findByOrderNo(orderNo); // 방금 취소했으므로 반드시 존재

        OrderDtos.CancelOrderResponse response = new OrderDtos.CancelOrderResponse(
                order.getId(),
                order.getOrderNo(),
                order.getStatus().name()
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 회원 주문 목록 조회 (OLV-063).
     * <p>
     * 상태 필터와 페이지네이션을 지원합니다.
     *
     * @param status   주문 상태 필터 (선택)
     * @param page     페이지 번호 (0-based)
     * @param size     페이지 크기
     * @param principal 인증된 회원
     * @return 주문 목록
     */
    @GetMapping
    public ResponseEntity<ApiResponse<java.util.List<OrderDtos.MyOrderListResponse>>> getMyOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Long memberId = principal.memberId();

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<OrderDtos.MyOrderListResponse> result = orderService.getMyOrders(memberId, status, pageable);

        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ResponseEntity.ok(ApiResponse.success(result.getContent(), meta));
    }

    /**
     * 회원 주문 상세 조회 (OLV-063).
     * <p>
     * 주문 상품, 배송지 정보, 상태 변경 이력을 포함합니다.
     *
     * @param orderNo   주문 번호
     * @param principal 인증된 회원
     * @return 주문 상세
     */
    @GetMapping("/{orderNo}")
    public ResponseEntity<ApiResponse<OrderDtos.MyOrderDetailResponse>> getMyOrderDetail(
            @PathVariable String orderNo,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Long memberId = principal.memberId();
        OrderDtos.MyOrderDetailResponse response = orderService.getMyOrderDetail(memberId, orderNo);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
