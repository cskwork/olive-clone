package com.olive.commerce.payment;

import com.olive.commerce.payment.Payment.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
