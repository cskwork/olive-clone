package com.olive.commerce.payment;

import com.olive.commerce.payment.Payment.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Payment JPA Repository.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * 주문 ID로 결제 조회 (1:1 관계).
     */
    Optional<Payment> findByOrderId(Long orderId);

    /**
     * PK로 결제 조회 + 행 비관적 쓰기 락 (FOR UPDATE).
     * <p>
     * 환불 누적 한도 검사 전에 Payment 행을 잠가, 동시 환불 승인이
     * 결제 금액을 초과하는 race(과다 환불)를 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Long paymentId);

    /**
     * PG payment key로 결제 조회 (webhook용).
     */
    Optional<Payment> findByPaymentKey(String paymentKey);

    /**
     * Idempotency key로 결제 조회.
     */
    Optional<Payment> findByIdempotencyKey(UUID idempotencyKey);

    /**
     * 주문 ID와 결제 상태로 조회.
     */
    Optional<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatus status);

    /**
     * PAYMENT_PENDING 상태로 N분 이상 경과된 결제를 조회합니다 (배치 처리용, PRD §17).
     * idx_payments_status_created 인덱스 활용.
     *
     * @param status         결제 상태 (PAYMENT_PENDING)
     * @param cutoffTime     기준 시점 (created_at < cutoffTime인 것만 대상)
     * @return 대상 결제 목록
     */
    @Query("SELECT p FROM Payment p JOIN p.order o WHERE o.status = 'PAYMENT_PENDING' AND p.createdAt < :cutoffTime")
    List<Payment> findPendingPaymentsOlderThan(@Param("cutoffTime") OffsetDateTime cutoffTime);
}
