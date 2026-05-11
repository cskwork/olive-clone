package com.olive.commerce.cart;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * CartItem Repository (PRD §6.4).
 */
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /**
     * 장바구니 ID로 모든 아이템 조회.
     */
    List<CartItem> findByCartId(Long cartId);

    /**
     * 장바구니 + 옵션 ID로 아이템 조회 (UNIQUE 제약 활용).
     */
    Optional<CartItem> findByCartIdAndProductOptionId(Long cartId, Long productOptionId);

    /**
     * 특정 옵션이 어떤 장바구니에 있는지 조회 (병합 시 활용).
     */
    @Query("SELECT ci FROM CartItem ci WHERE ci.cart.memberId = :memberId AND ci.productOptionId IN :optionIds")
    List<CartItem> findByMemberIdAndProductOptionIds(@Param("memberId") Long memberId, @Param("optionIds") List<Long> optionIds);
}
