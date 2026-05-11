package com.olive.commerce.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Inventory DTO들.
 */
public class InventoryDtos {

    /**
     * 재고 조회 응답 (Admin API).
     */
    public record InventoryResponse(
            Long id,
            Long productOptionId,
            Integer totalQuantity,
            Integer reservedQuantity,
            Integer availableQuantity,
            Instant updatedAt
    ) {
        public static InventoryResponse from(Inventory inventory) {
            return new InventoryResponse(
                    inventory.getId(),
                    inventory.getProductOptionId(),
                    inventory.getTotalQuantity(),
                    inventory.getReservedQuantity(),
                    inventory.getAvailableQuantity(),
                    inventory.getUpdatedAt()
            );
        }
    }

    /**
     * 재고 수동 조정 요청 (Admin API).
     */
    public record AdjustRequest(
            @NotNull(message = "delta는 필수입니다")
            Integer delta,
            @NotBlank(message = "reason은 필수입니다")
            String reason
    ) {
        public AdjustRequest {
            if (delta == 0) {
                throw new IllegalArgumentException("delta cannot be zero");
            }
        }
    }

    /**
     * 재고 수동 조정 응답 (Admin API).
     */
    public record AdjustResponse(
            Long productOptionId,
            Integer newTotalQuantity,
            Integer newAvailableQuantity
    ) {
        public static AdjustResponse from(Inventory inventory) {
            return new AdjustResponse(
                    inventory.getProductOptionId(),
                    inventory.getTotalQuantity(),
                    inventory.getAvailableQuantity()
            );
        }
    }

    /**
     * 예약 요청 (Service API).
     */
    public record ReserveRequest(
            Long orderId,
            java.util.List<ReserveItemDto> items,
            java.time.Duration ttl
    ) {
        public record ReserveItemDto(
                Long optionId,
                int quantity
            ) {
            public ReserveItemDto {
                if (quantity <= 0) {
                    throw new IllegalArgumentException("quantity must be positive");
                }
            }
        }
    }
}
