package com.olive.commerce.cart;

import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.inventory.Inventory;
import com.olive.commerce.inventory.InventoryRepository;
import com.olive.commerce.product.ProductOption;
import com.olive.commerce.product.ProductOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 장바구니 검증 헬퍼.
 *
 * <p>CartService에서 분리한 상품 옵션 상태 및 재고 검증 로직.
 * 트랜잭션 없음 — 호출자(CartService)의 트랜잭션 컨텍스트에서 실행된다.
 */
@Component
@RequiredArgsConstructor
public class CartValidationHelper {

    private final ProductOptionRepository productOptionRepository;
    private final InventoryRepository inventoryRepository;

    /**
     * 상품 옵션 조회 및 구매 가능 상태 검증.
     *
     * <p>옵션이 없거나 STOPPED/HIDDEN 상태이면 예외를 던진다.
     *
     * @param productOptionId 상품 옵션 ID
     * @return 유효한 ProductOption
     * @throws BusinessException 옵션 미존재 또는 구매 불가 상태
     */
    public ProductOption validateOption(Long productOptionId) {
        ProductOption option = productOptionRepository.findById(productOptionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                "Product option not found: " + productOptionId));

        if (option.getStatus() == ProductOption.OptionStatus.STOPPED ||
            option.getStatus() == ProductOption.OptionStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "Product option is not available for purchase: " + option.getStatus());
        }

        return option;
    }

    /**
     * 재고 조회 및 요청 수량 검증.
     *
     * <p>재고가 없거나 요청 수량을 충족하지 못하면 예외를 던진다.
     *
     * @param productOptionId 상품 옵션 ID
     * @param requestedQuantity 요청 수량
     * @return 유효한 Inventory
     * @throws BusinessException 재고 미존재 또는 부족
     */
    public Inventory validateInventory(Long productOptionId, int requestedQuantity) {
        Inventory inventory = inventoryRepository.findByProductOptionId(productOptionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                "Inventory not found for option: " + productOptionId));

        if (!inventory.hasAvailable(requestedQuantity)) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY,
                "Insufficient inventory. Available: " + inventory.getAvailableQuantity() +
                ", Requested: " + requestedQuantity);
        }

        return inventory;
    }

    /**
     * 재고 조회만 수행 (수량 검증 없음).
     *
     * <p>병합 루프 등 직접 가용 수량을 비교해야 하는 경우에 사용.
     *
     * @param productOptionId 상품 옵션 ID
     * @return Inventory 또는 null (재고 레코드 없음)
     */
    public Inventory findInventory(Long productOptionId) {
        return inventoryRepository.findByProductOptionId(productOptionId).orElse(null);
    }
}
