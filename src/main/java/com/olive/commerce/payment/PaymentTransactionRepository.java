package com.olive.commerce.payment;

import com.olive.commerce.payment.PaymentTransaction.TransactionKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * PaymentTransaction JPA Repository.
 */
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /**
     * 결제, 종류, idempotency key로 트랜잭션 조회 (멱등성 체크용).
     * UNIQUE 제약 uq_payment_transaction_replay에 의해 최대 1건.
     */
    @Query("""
            SELECT pt FROM PaymentTransaction pt
            WHERE pt.payment.id = :paymentId
              AND pt.kind = :kind
              AND pt.idempotencyKey = :idempotencyKey
            """)
    Optional<PaymentTransaction> findByPaymentIdAndKindAndIdempotencyKey(
            @Param("paymentId") Long paymentId,
            @Param("kind") TransactionKind kind,
            @Param("idempotencyKey") UUID idempotencyKey
    );
}
