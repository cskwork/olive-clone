package com.olive.commerce.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 주문 상품 Repository.
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /**
     * 주문 ID로 주문 상품 목록 조회.
     */
    List<OrderItem> findByOrderId(Long orderId);
}
