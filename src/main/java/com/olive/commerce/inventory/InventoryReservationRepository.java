package com.olive.commerce.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * InventoryReservation Repository (PRD §17.2).
 */
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {

    /**
     * 주문+옵션 조합으로 예약 조회 (UNIQUE 제약 활용).
     */
    Optional<InventoryReservation> findByOrderIdAndProductOptionId(Long orderId, Long productOptionId);

    /**
     * 주문의 모든 예약 조회.
     */
    List<InventoryReservation> findByOrderId(Long orderId);

    /**
     * 만료된 HELD 상태 예약 조회 (배치 처리용, PRD §17.2).
     * idx_reservations_status_expires 인덱스 활용.
     */
    @Query("""
            SELECT r FROM InventoryReservation r
            WHERE r.status = 'HELD' AND r.expiresAt < :now
            """)
    List<InventoryReservation> findExpiredHeldReservations(@Param("now") Instant now);
}
