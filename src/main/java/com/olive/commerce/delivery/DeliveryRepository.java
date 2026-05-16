package com.olive.commerce.delivery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 배송 Repository.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    /**
     * 주문 ID로 배송 목록 조회.
     */
    List<Delivery> findByOrderId(Long orderId);

    /**
     * 운송장 번호로 배송 조회.
     */
    Optional<Delivery> findByInvoiceNo(String invoiceNo);

    /**
     * 상태별 배송 목록 조회 (페이지 지원).
     */
    @Query("SELECT d FROM Delivery d WHERE d.status = :status ORDER BY d.createdAt DESC")
    List<Delivery> findByStatus(@Param("status") Delivery.DeliveryStatus status);

    /**
     * 관리자용 배송 목록 조회 (상태 필터, 페이지 지원).
     */
    @Query("SELECT d FROM Delivery d WHERE (:status IS NULL OR d.status = :status) ORDER BY d.createdAt DESC")
    Page<Delivery> findByStatusOptional(@Param("status") Delivery.DeliveryStatus status, Pageable pageable);
}
