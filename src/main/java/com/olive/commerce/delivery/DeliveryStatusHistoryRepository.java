package com.olive.commerce.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 배송 상태 변경 이력 Repository.
 */
public interface DeliveryStatusHistoryRepository extends JpaRepository<DeliveryStatusHistory, Long> {

    /**
     * 배송 ID로 상태 변경 이력 조회 (최신순).
     */
    @Query("SELECT h FROM DeliveryStatusHistory h WHERE h.delivery.id = :deliveryId ORDER BY h.createdAt DESC")
    List<DeliveryStatusHistory> findByDeliveryIdOrderByCreatedAtDesc(@Param("deliveryId") Long deliveryId);
}
