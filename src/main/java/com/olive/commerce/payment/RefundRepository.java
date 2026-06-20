package com.olive.commerce.payment;

import com.olive.commerce.payment.Refund.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Refund 엔티티 리포지토리.
 */
public interface RefundRepository extends JpaRepository<Refund, Long> {

    /**
     * 주문 ID로 환불 목록 조회.
     */
    List<Refund> findByOrderId(Long orderId);

    /**
     * 결제 ID로 환불 목록 조회.
     */
    List<Refund> findByPaymentId(Long paymentId);

    /**
     * 주문 ID와 상태로 환불 조회.
     */
    Optional<Refund> findByOrderIdAndStatus(Long orderId, RefundStatus status);

    /**
     * 결제 ID별 총 환불 금액 집계 (APPROVED만).
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.payment.id = :paymentId AND r.status = 'APPROVED'")
    java.math.BigDecimal sumApprovedAmountByPaymentId(@Param("paymentId") Long paymentId);

    /**
     * 결제 ID별 미확정+확정 환불 금액 집계 (REQUESTED + APPROVED).
     * <p>
     * 진행 중인(REQUESTED) 환불까지 누적 한도에 포함시켜, 겹치는 in-flight 환불이
     * 책임 금액을 이중으로 늘리지 못하게 한다 (과다 환불 방지).
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.payment.id = :paymentId AND r.status IN ('REQUESTED', 'APPROVED')")
    java.math.BigDecimal sumRequestedAndApprovedAmountByPaymentId(@Param("paymentId") Long paymentId);

    /**
     * 상태별 환불 목록 페이징 조회.
     */
    List<Refund> findByStatus(RefundStatus status);

    /**
     * 주문 번호로 환불 조회 (order join).
     */
    @Query("SELECT r FROM Refund r JOIN r.order o WHERE o.orderNo = :orderNo")
    List<Refund> findByOrderNo(@Param("orderNo") String orderNo);
}
