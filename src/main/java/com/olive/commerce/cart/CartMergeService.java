package com.olive.commerce.cart;

import com.olive.commerce.inventory.Inventory;
import com.olive.commerce.product.ProductOption;
import com.olive.commerce.product.ProductOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 익명 장바구니 → 회원 장바구니 병합 처리기.
 *
 * <p>CartService에서 분리한 병합 루프 로직.
 * 트랜잭션 없음 — CartService.mergeCart()의 @Transactional 컨텍스트 내에서 실행된다 (PRD §6.4).
 */
@Component
@RequiredArgsConstructor
public class CartMergeService {

    private final CartItemRepository cartItemRepository;
    private final ProductOptionRepository productOptionRepository;
    private final CartValidationHelper cartValidationHelper;

    /**
     * 익명 장바구니 아이템 목록을 회원 장바구니에 병합.
     *
     * <p>로직: union by product_option_id, sum quantities, cap at available_quantity.
     * 유효하지 않은 옵션(STOPPED/HIDDEN/미존재) 및 재고 없는 아이템은 건너뜀.
     *
     * @param memberCart 회원 Cart 엔티티 (영속 상태)
     * @param anonItems  익명 장바구니 아이템 목록
     * @return 병합된 아이템 수
     */
    public int mergeItems(Cart memberCart, List<AnonymousCartItem> anonItems) {
        int mergedCount = 0;

        for (AnonymousCartItem anonItem : anonItems) {
            // 옵션 상태 검증 (유효하지 않으면 건너뜀)
            ProductOption option = productOptionRepository.findById(anonItem.productOptionId())
                .orElse(null);

            if (option == null ||
                option.getStatus() == ProductOption.OptionStatus.STOPPED ||
                option.getStatus() == ProductOption.OptionStatus.HIDDEN) {
                continue;
            }

            // 재고 확인 (없으면 건너뜀)
            Inventory inventory = cartValidationHelper.findInventory(anonItem.productOptionId());
            if (inventory == null) {
                continue;
            }

            // 기존 회원 카트 아이템 확인
            CartItem existingItem = cartItemRepository
                .findByCartIdAndProductOptionId(memberCart.getId(), anonItem.productOptionId())
                .orElse(null);

            int finalQuantity;
            if (existingItem != null) {
                // 수량 합산, 재고 상한 적용
                int sumQuantity = existingItem.getQuantity() + anonItem.quantity();
                finalQuantity = Math.min(sumQuantity, inventory.getAvailableQuantity());
                existingItem.updateQuantity(finalQuantity);
                cartItemRepository.save(existingItem);
            } else {
                // 새 아이템, 재고 상한 적용
                finalQuantity = Math.min(anonItem.quantity(), inventory.getAvailableQuantity());
                if (finalQuantity > 0) {
                    CartItem newItem = CartItem.create(memberCart, anonItem.productOptionId(), finalQuantity);
                    cartItemRepository.save(newItem);
                }
            }

            if (finalQuantity > 0) {
                mergedCount++;
            }
        }

        return mergedCount;
    }
}
