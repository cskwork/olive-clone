package com.olive.commerce.admin;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.inventory.Inventory;
import com.olive.commerce.inventory.InventoryDtos;
import com.olive.commerce.inventory.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Inventory 관리자 API (OLV-031).
 *
 * <p>경로: {@code /api/admin/inventories}
 *
 * <p>권한: {@code PRODUCT_ADMIN} 또는 {@code INVENTORY_ADMIN}
 */
@RestController
@RequestMapping("/api/admin/inventories")
public class InventoryAdminController {

    private final InventoryService inventoryService;

    public InventoryAdminController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * 상품별 재고 목록 조회.
     *
     * <p>productId는 필수입니다. 제공하지 않으면 400 오류를 반환합니다.
     * 전체 재고를 조회하는 무제한 쿼리는 허용하지 않습니다.
     *
     * @param productId 상품 ID (필수)
     * @return 옵션별 재고 현황
     */
    @GetMapping
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<List<InventoryDtos.InventoryResponse>> list(
            @RequestParam Long productId
    ) {
        List<Inventory> inventories = inventoryService.findByProductId(productId);

        List<InventoryDtos.InventoryResponse> responses = inventories.stream()
                .map(InventoryDtos.InventoryResponse::from)
                .toList();

        return ApiResponse.success(responses);
    }

    /**
     * 옵션별 재고 상세 조회.
     *
     * @param optionId 옵션 ID
     * @return 재고 상세
     */
    @GetMapping("/options/{optionId}")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<InventoryDtos.InventoryResponse> getByOptionId(@PathVariable Long optionId) {
        Inventory inventory = inventoryService.findByOptionId(optionId);
        return ApiResponse.success(InventoryDtos.InventoryResponse.from(inventory));
    }

    /**
     * 재고 수동 조정 (ADMIN_ADJUST).
     *
     * @param optionId 옵션 ID
     * @param request  조정 요청 (delta, reason)
     * @return 조정 후 재고 상태
     */
    @PostMapping("/options/{optionId}/adjust")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ResponseEntity<ApiResponse<InventoryDtos.AdjustResponse>> adjust(
            @PathVariable Long optionId,
            @Valid @RequestBody InventoryDtos.AdjustRequest request
    ) {
        // TODO: adminId는 SecurityContext에서 추출 (현재는 null)
        inventoryService.adjust(optionId, request.delta(), request.reason(), null);

        Inventory updated = inventoryService.findByOptionId(optionId);
        InventoryDtos.AdjustResponse response = InventoryDtos.AdjustResponse.from(updated);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 만료된 예약 일괄 해제 (배치 작업 트리거용).
     *
     * @return 해제된 예약 수
     */
    @PostMapping("/release-expired")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<String> releaseExpired() {
        int released = inventoryService.releaseExpired();
        return ApiResponse.success("Released " + released + " expired reservations");
    }
}
