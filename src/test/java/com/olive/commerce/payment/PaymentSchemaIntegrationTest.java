package com.olive.commerce.payment;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * OLV-070 Acceptance Criteria 검증:
 *  - V9 마이그레이션이 적용됐는가? (flyway_schema_history 행)
 *  - payments / payment_transactions / refunds 테이블이 존재하는가?
 *  - idempotency_key UNIQUE 제약이 존재하는가?
 *  - payment_transactions의 (payment_id, kind, idempotency_key) UNIQUE이 존재하는가? (AC2)
 *  - 전체 라이프사이클(request → webhook → refund) INSERT/SELECT 왕복 가능?
 *  - 인덱스가 존재하는가?
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class PaymentSchemaIntegrationTest extends PostgresIntegrationSupport {

    @Autowired
    private EntityManager em;

    @Test
    void v9MigrationIsApplied() {
        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '9' AND success = TRUE
                """).getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    void paymentsTableExistsWithConstraints() {
        // Check payments table has status check constraint
        String statusCheck = (String) em.createNativeQuery("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'payments'::regclass
                  AND conname = 'payments_status_check'
                """).getSingleResult();

        assertThat(statusCheck).isNotNull();
        assertThat(statusCheck).contains("READY");
        assertThat(statusCheck).contains("REQUESTED");
        assertThat(statusCheck).contains("APPROVED");

        // Check method check constraint
        String methodCheck = (String) em.createNativeQuery("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'payments'::regclass
                  AND conname = 'payments_method_check'
                """).getSingleResult();

        assertThat(methodCheck).isNotNull();
        assertThat(methodCheck).contains("CARD");
        assertThat(methodCheck).contains("KAKAO_PAY");

        // Check idempotency_key UNIQUE constraint
        @SuppressWarnings("unchecked")
        var constraints = em.createNativeQuery("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'payments'::regclass
                  AND contype = 'u'
                  AND conname LIKE '%idempotency%'
                """).getResultList();

        assertThat(constraints).isNotEmpty();

        // Check order_id UNIQUE constraint
        Number orderUnique = (Number) em.createNativeQuery("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conrelid = 'payments'::regclass
                  AND contype = 'u'
                  AND conname LIKE '%order_id%'
                """).getSingleResult();

        assertThat(orderUnique.intValue()).isGreaterThan(0);
    }

    @Test
    void paymentTransactionsTableExistsWithConstraints() {
        // Check payment_transactions table has kind check constraint
        String kindCheck = (String) em.createNativeQuery("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'payment_transactions'::regclass
                  AND conname = 'payment_transactions_kind_check'
                """).getSingleResult();

        assertThat(kindCheck).isNotNull();
        assertThat(kindCheck).contains("REQUEST");
        assertThat(kindCheck).contains("APPROVE");
        assertThat(kindCheck).contains("WEBHOOK");

        // Check pg_response_json is JSONB type
        @SuppressWarnings("unchecked")
        var columns = em.createNativeQuery("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_name = 'payment_transactions'
                AND column_name = 'pg_response_json'
                """).getResultList();

        assertThat(columns).hasSize(1);
        assertThat(columns.get(0)).isEqualTo("jsonb");

        // Check UNIQUE constraint on (payment_id, kind, idempotency_key)
        String uniqueConstraint = (String) em.createNativeQuery("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'payment_transactions'::regclass
                  AND conname = 'uq_payment_transaction_replay'
                """).getSingleResult();

        assertThat(uniqueConstraint).isNotNull();
        assertThat(uniqueConstraint).contains("payment_id");
        assertThat(uniqueConstraint).contains("kind");
        assertThat(uniqueConstraint).contains("idempotency_key");
    }

    @Test
    void refundsTableExistsWithConstraints() {
        // Check refunds table has status check constraint
        String statusCheck = (String) em.createNativeQuery("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'refunds'::regclass
                  AND conname = 'refunds_status_check'
                """).getSingleResult();

        assertThat(statusCheck).isNotNull();
        assertThat(statusCheck).contains("REQUESTED");
        assertThat(statusCheck).contains("APPROVED");
        assertThat(statusCheck).contains("FAILED");

        // Check amount > 0 constraint
        @SuppressWarnings("unchecked")
        var checks = em.createNativeQuery("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'refunds'::regclass
                  AND pg_get_constraintdef(oid) LIKE '%amount%'
                """).getResultList();

        assertThat(checks).isNotEmpty();
    }

    @Test
    void repositoryTest_FullLifecycle_InsertSelect() {
        // Given: create test member, address, order
        Long memberId = ((Number) em.createNativeQuery("""
                INSERT INTO members (email, password_hash, name, phone, status, grade_id)
                VALUES ('payment-test@example.com', '$2a$12$test', '결제테스트', '01012345678', 'ACTIVE',
                        (SELECT id FROM member_grades LIMIT 1))
                RETURNING id
                """).getSingleResult()).longValue();

        Long addressId = ((Number) em.createNativeQuery("""
                INSERT INTO member_addresses (member_id, recipient_name, phone,
                    zipcode, address_main, address_detail, is_default)
                VALUES (:memberId, '홍길동', '01012345678', '12345', '서울시 강남구', '101호', TRUE)
                RETURNING id
                """).setParameter("memberId", memberId).getSingleResult()).longValue();

        UUID idempotencyKey = UUID.randomUUID();

        Long orderId = ((Number) em.createNativeQuery("""
                INSERT INTO orders (member_id, delivery_address_id,
                    total_product_amount, discount_amount, point_used_amount,
                    delivery_fee, final_payment_amount, status)
                VALUES (:memberId, :addressId, 50000, 0, 0, 3000, 53000, 'PAYMENT_PENDING')
                RETURNING id
                """)
                .setParameter("memberId", memberId)
                .setParameter("addressId", addressId)
                .getSingleResult()).longValue();

        // When: payment REQUEST
        UUID paymentIdempotencyKey = UUID.randomUUID();
        Long paymentId = ((Number) em.createNativeQuery("""
                INSERT INTO payments (order_id, method, status, requested_amount,
                    idempotency_key, requested_at)
                VALUES (:orderId, 'CARD', 'REQUESTED', 53000, :idempotencyKey, now())
                RETURNING id
                """)
                .setParameter("orderId", orderId)
                .setParameter("idempotencyKey", paymentIdempotencyKey)
                .getSingleResult()).longValue();

        // Record REQUEST transaction
        em.createNativeQuery("""
                INSERT INTO payment_transactions (payment_id, kind, pg_response_json,
                    http_status, idempotency_key)
                VALUES (:paymentId, 'REQUEST', '{"status":"pending"}'::jsonb, 200, :idempotencyKey)
                """)
                .setParameter("paymentId", paymentId)
                .setParameter("idempotencyKey", paymentIdempotencyKey)
                .executeUpdate();

        // Payment APPROVED
        em.createNativeQuery("""
                UPDATE payments
                SET status = 'APPROVED',
                    approved_amount = 53000,
                    payment_key = 'pg_key_12345',
                    pg_provider = 'toss',
                    approved_at = now()
                WHERE id = :paymentId
                """).setParameter("paymentId", paymentId).executeUpdate();

        // Record APPROVE transaction
        em.createNativeQuery("""
                INSERT INTO payment_transactions (payment_id, kind, pg_response_json,
                    http_status, idempotency_key)
                VALUES (:paymentId, 'APPROVE', '{"status":"approved","payment_key":"pg_key_12345"}'::jsonb, 200, :idempotencyKey)
                """)
                .setParameter("paymentId", paymentId)
                .setParameter("idempotencyKey", UUID.randomUUID())
                .executeUpdate();

        // Record WEBHOOK transaction (PG-originated, no idempotency_key)
        em.createNativeQuery("""
                INSERT INTO payment_transactions (payment_id, kind, pg_response_json,
                    http_status, idempotency_key)
                VALUES (:paymentId, 'WEBHOOK', '{"event":"payment.approved"}'::jsonb, 200, NULL)
                """)
                .setParameter("paymentId", paymentId)
                .executeUpdate();

        // Refund REQUESTED
        Long refundId = ((Number) em.createNativeQuery("""
                INSERT INTO refunds (payment_id, order_id, amount, reason, status)
                VALUES (:paymentId, :orderId, 53000, '단순 변심', 'REQUESTED')
                RETURNING id
                """)
                .setParameter("paymentId", paymentId)
                .setParameter("orderId", orderId)
                .getSingleResult()).longValue();

        em.flush();
        em.clear();

        // Then: read back and verify
        Object[] payment = (Object[]) em.createNativeQuery("""
                SELECT status, requested_amount, approved_amount, payment_key, pg_provider, idempotency_key
                FROM payments WHERE id = :id
                """).setParameter("id", paymentId).getSingleResult();

        assertThat(payment[0]).isEqualTo("APPROVED");
        assertThat(((Number) payment[1]).doubleValue()).isEqualTo(53000.00);
        assertThat(((Number) payment[2]).doubleValue()).isEqualTo(53000.00);
        assertThat(payment[3]).isEqualTo("pg_key_12345");
        assertThat(payment[4]).isEqualTo("toss");

        // Verify payment_transactions
        @SuppressWarnings("unchecked")
        List<Object[]> transactions = em.createNativeQuery("""
                SELECT kind, pg_response_json, idempotency_key
                FROM payment_transactions WHERE payment_id = :paymentId
                ORDER BY created_at
                """).setParameter("paymentId", paymentId).getResultList();

        assertThat(transactions).hasSize(3);
        assertThat(transactions.get(0)[0]).isEqualTo("REQUEST");
        assertThat(transactions.get(1)[0]).isEqualTo("APPROVE");
        assertThat(transactions.get(2)[0]).isEqualTo("WEBHOOK");
        assertThat(transactions.get(2)[2]).isNull(); // WEBHOOK has no idempotency_key

        // Verify refund
        Object[] refund = (Object[]) em.createNativeQuery("""
                SELECT amount, reason, status, pg_refund_key
                FROM refunds WHERE id = :id
                """).setParameter("id", refundId).getSingleResult();

        assertThat(((Number) refund[0]).doubleValue()).isEqualTo(53000.00);
        assertThat(refund[1]).isEqualTo("단순 변심");
        assertThat(refund[2]).isEqualTo("REQUESTED");
        assertThat(refund[3]).isNull(); // pg_refund_key is NULL until PG approves
    }

    @Test
    void ac2_ReplayProtection_UniqConstraintViolatesOnDuplicate() {
        // Given: create test member, address, order, payment
        Long memberId = ((Number) em.createNativeQuery("""
                INSERT INTO members (email, password_hash, name, phone, status, grade_id)
                VALUES ('replay-test@example.com', '$2a$12$test', '재실행테스트', '01012345678', 'ACTIVE',
                        (SELECT id FROM member_grades LIMIT 1))
                RETURNING id
                """).getSingleResult()).longValue();

        Long addressId = ((Number) em.createNativeQuery("""
                INSERT INTO member_addresses (member_id, recipient_name, phone,
                    zipcode, address_main, address_detail, is_default)
                VALUES (:memberId, '김철수', '01012345678', '12345', '서울시 강남구', '102호', TRUE)
                RETURNING id
                """).setParameter("memberId", memberId).getSingleResult()).longValue();

        Long orderId = ((Number) em.createNativeQuery("""
                INSERT INTO orders (member_id, delivery_address_id,
                    total_product_amount, discount_amount, point_used_amount,
                    delivery_fee, final_payment_amount, status)
                VALUES (:memberId, :addressId, 10000, 0, 0, 0, 10000, 'PAYMENT_PENDING')
                RETURNING id
                """)
                .setParameter("memberId", memberId)
                .setParameter("addressId", addressId)
                .getSingleResult()).longValue();

        Long paymentId = ((Number) em.createNativeQuery("""
                INSERT INTO payments (order_id, method, status, requested_amount,
                    idempotency_key, requested_at)
                VALUES (:orderId, 'CARD', 'REQUESTED', 10000, :idempotencyKey, now())
                RETURNING id
                """)
                .setParameter("orderId", orderId)
                .setParameter("idempotencyKey", UUID.randomUUID())
                .getSingleResult()).longValue();

        UUID idempotencyKey = UUID.randomUUID();

        // When: insert first transaction
        em.createNativeQuery("""
                INSERT INTO payment_transactions (payment_id, kind, pg_response_json,
                    http_status, idempotency_key)
                VALUES (:paymentId, 'REQUEST', '{"status":"pending"}'::jsonb, 200, :idempotencyKey)
                """)
                .setParameter("paymentId", paymentId)
                .setParameter("idempotencyKey", idempotencyKey)
                .executeUpdate();

        em.flush();
        em.clear();

        // Then: inserting same (payment_id, kind, idempotency_key) should violate UNIQUE constraint
        org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class, // org.hibernate.exception.ConstraintViolationException wrapped in PersistenceException
                () -> {
                    em.createNativeQuery("""
                            INSERT INTO payment_transactions (payment_id, kind, pg_response_json,
                                http_status, idempotency_key)
                            VALUES (:paymentId, 'REQUEST', '{"status":"pending"}'::jsonb, 200, :idempotencyKey)
                            """)
                            .setParameter("paymentId", paymentId)
                            .setParameter("idempotencyKey", idempotencyKey)
                            .executeUpdate();
                    em.flush();
                }
        );
    }

    @Test
    void idx_Payments_StatusCreatedAt_Exists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'payments' AND indexname = 'idx_payments_status_created'
                """).getResultList();

        assertThat(indexes).hasSize(1);
    }

    @Test
    void idx_Payments_PaymentKey_Exists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'payments' AND indexname = 'idx_payments_payment_key'
                """).getResultList();

        assertThat(indexes).hasSize(1);
    }

    @Test
    void idx_PaymentTransactions_PaymentCreatedAt_Exists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'payment_transactions' AND indexname = 'idx_payment_transactions_payment_created'
                """).getResultList();

        assertThat(indexes).hasSize(1);
    }

    @Test
    void idx_Refunds_PaymentId_Exists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'refunds' AND indexname = 'idx_refunds_payment_id'
                """).getResultList();

        assertThat(indexes).hasSize(1);
    }

    @Test
    void idx_Refunds_OrderId_Exists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'refunds' AND indexname = 'idx_refunds_order_id'
                """).getResultList();

        assertThat(indexes).hasSize(1);
    }

    @Test
    void idx_Refunds_StatusRequestedAt_Exists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'refunds' AND indexname = 'idx_refunds_status_requested'
                """).getResultList();

        assertThat(indexes).hasSize(1);
    }
}
