package com.olive.commerce.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 주문 가격 요약 Repository.
 */
public interface OrderPriceSummaryRepository extends JpaRepository<OrderPriceSummary, Long> {

    /**
     * 주문 ID로 가격 요약 조회 (1:1 관계).
     */
    Optional<OrderPriceSummary> findByOrderId(Long orderId);
}
