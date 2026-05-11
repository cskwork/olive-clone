package com.olive.commerce.cart;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Cart Repository (PRD §6.4).
 */
public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * 회원 ID로 장바구니 조회.
     */
    Optional<Cart> findByMemberId(Long memberId);

    /**
     * 회원 ID로 장바구니 존재 확인.
     */
    boolean existsByMemberId(Long memberId);
}
