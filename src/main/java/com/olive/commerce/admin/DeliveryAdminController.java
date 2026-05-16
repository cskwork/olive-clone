package com.olive.commerce.admin;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.delivery.Delivery;
import com.olive.commerce.delivery.DeliveryStatusHistory;
import com.olive.commerce.delivery.DeliveryRepository;
import com.olive.commerce.delivery.DeliveryService;
import com.olive.commerce.delivery.DeliveryDtos;
import com.olive.commerce.delivery.DeliveryStatusHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 배송 관리자 API.
 * <p>경로: {@code /api/admin/deliveries}
 * <p>권한: {@code ORDER_ADMIN} 또는 {@code SUPER_ADMIN}
 */
@RestController
@RequestMapping("/api/admin/deliveries")
public class DeliveryAdminController {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryStatusHistoryRepository historyRepository;
    private final DeliveryService deliveryService;

    public DeliveryAdminController(DeliveryRepository deliveryRepository,
                                   DeliveryStatusHistoryRepository historyRepository,
                                   DeliveryService deliveryService) {
        this.deliveryRepository = deliveryRepository;
        this.historyRepository = historyRepository;
        this.deliveryService = deliveryService;
    }

    /**
     * 관리자 배송 목록 조회.
     * <p>
     * 상태 필터와 페이지네이션을 지원합니다.
     *
     * @param status   배송 상태 필터 (선택)
     * @param page     페이지 번호 (0-based)
     * @param size     페이지 크기
     * @param principal 인증된 관리자
     * @return 배송 목록
     */
    @GetMapping
    @PreAuthorize("hasRole('ORDER_ADMIN')")
    public ResponseEntity<ApiResponse<DeliveryDtos.DeliveryAdminListResponse>> getAdminDeliveries(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Delivery.DeliveryStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = Delivery.DeliveryStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                // 유효하지 않은 상태는 무시
            }
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Delivery> result = deliveryRepository.findByStatusOptional(statusEnum, pageable);

        DeliveryDtos.DeliveryAdminListResponse response = DeliveryDtos.DeliveryAdminListResponse.of(
            result.getContent(),
            page,
            size,
            result.getTotalElements()
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 배송 상세 조회.
     *
     * @param id       배송 ID
     * @param principal 인증된 관리자
     * @return 배송 상세 (상태 이력 포함)
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ORDER_ADMIN')")
    public ResponseEntity<ApiResponse<DeliveryDtos.DeliveryDetailResponse>> getAdminDeliveryDetail(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Delivery delivery = deliveryRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Delivery not found: " + id));

        List<DeliveryStatusHistory> histories = historyRepository
            .findByDeliveryIdOrderByCreatedAtDesc(id);

        DeliveryDtos.DeliveryDetailResponse response = DeliveryDtos.DeliveryDetailResponse.from(
            delivery, histories
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 운송장 발급 재시도.
     * <p>
     * 택배사 API 실패로 재시도 큐에 있는 항목을 즉시 재처리합니다.
     *
     * @param id       배송 ID
     * @param principal 인증된 관리자
     * @return 재시도 결과
     */
    @PostMapping("/{id}/issue-invoice")
    @PreAuthorize("hasRole('ORDER_ADMIN')")
    public ResponseEntity<ApiResponse<DeliveryDtos.InvoiceRetryResponse>> retryIssueInvoice(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        try {
            deliveryService.issueInvoice(id);
            return ResponseEntity.ok(ApiResponse.success(DeliveryDtos.InvoiceRetryResponse.ok()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(
                DeliveryDtos.InvoiceRetryResponse.fail(e.getMessage())
            ));
        }
    }
}
