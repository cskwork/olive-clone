package com.olive.commerce.admin;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.order.OrderDtos;
import com.olive.commerce.order.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 주문 관리자 API (OLV-062).
 * <p>경로: {@code /api/admin/orders}
 * <p>권한: {@code ORDER_ADMIN} 또는 {@code SUPER_ADMIN}
 */
@RestController
@RequestMapping("/api/admin/orders")
public class OrderAdminController {

    private final OrderService orderService;

    public OrderAdminController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 관리자 강제 주문 취소 (OLV-062).
     * <p>
     * 비종단 상태(CANCELED/REFUNDED/FAILED 제외)의 주문을 강제로 취소합니다.
     *
     * @param orderId 주문 ID (PK)
     * @param request 취소 요청 (사유 필수)
     * @param principal 인증된 관리자
     * @return 취소 응답
     */
    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasRole('ORDER_ADMIN')")
    public ResponseEntity<ApiResponse<OrderDtos.CancelOrderResponse>> cancelOrder(
            @PathVariable Long orderId,
            @Valid @RequestBody AdminCancelOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Long adminId = principal.memberId();

        orderService.cancelAdminOrder(orderId, request.reason(), adminId);

        // 취소된 주문 정보를 조회하여 응답 생성
        var order = orderService.findById(orderId);

        OrderDtos.CancelOrderResponse response = new OrderDtos.CancelOrderResponse(
                order.getId(),
                order.getOrderNo(),
                order.getStatus().name()
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 관리자 주문 취소 요청 (사유 필수).
     */
    public record AdminCancelOrderRequest(
            @NotBlank(message = "취소 사유는 필수입니다")
            String reason
    ) {}

    /**
     * 관리자 주문 목록 조회 (OLV-063).
     * <p>
     * 상태, 회원 ID, 날짜 범위 필터와 페이지네이션을 지원합니다.
     * PII가 마스킹됩니다.
     *
     * @param status   주문 상태 필터 (선택)
     * @param memberId 회원 ID 필터 (선택)
     * @param from     시작일시 (선택)
     * @param to       종료일시 (선택)
     * @param page     페이지 번호 (0-based)
     * @param size     페이지 크기
     * @param principal 인증된 관리자
     * @return 주문 목록 (PII 마스킹됨)
     */
    @GetMapping
    @PreAuthorize("hasRole('ORDER_ADMIN')")
    public ResponseEntity<ApiResponse<List<OrderDtos.AdminOrderListResponse>>> getAdminOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<OrderDtos.AdminOrderListResponse> result = orderService.getAdminOrders(status, memberId, from, to, pageable);

        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ResponseEntity.ok(ApiResponse.success(result.getContent(), meta));
    }

    /**
     * 관리자 주문 상세 조회 (OLV-063).
     * <p>
     * 모든 정보 포함 (PII 마스킹 없음).
     *
     * @param orderId   주문 ID
     * @param principal 인증된 관리자
     * @return 주문 상세
     */
    @GetMapping("/{orderId}")
    @PreAuthorize("hasRole('ORDER_ADMIN')")
    public ResponseEntity<ApiResponse<OrderDtos.AdminOrderDetailResponse>> getAdminOrderDetail(
            @PathVariable Long orderId,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        OrderDtos.AdminOrderDetailResponse response = orderService.getAdminOrderDetail(orderId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 관리자 주문 상태 변경 (OLV-063).
     * <p>
     * 허용된 전이만 가능합니다.
     *
     * @param orderId   주문 ID
     * @param request   상태 변경 요청
     * @param principal 인증된 관리자
     * @return 상태 변경 응답
     */
    @PatchMapping("/{orderId}/status")
    @PreAuthorize("hasRole('ORDER_ADMIN')")
    public ResponseEntity<ApiResponse<OrderDtos.StatusUpdateResponse>> updateOrderStatus(
            @PathVariable Long orderId,
            @Valid @RequestBody OrderDtos.StatusUpdateRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Long adminId = principal.memberId();
        OrderDtos.StatusUpdateResponse response = orderService.updateOrderStatus(orderId, request, adminId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
