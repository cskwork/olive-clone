package com.olive.commerce.order;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * OLV-060 Acceptance Criteria 검증:
 *  - V7 마이그레이션이 적용됐는가? (flyway_schema_history 행)
 *  - orders / order_items / order_price_summaries / order_status_histories
 *    한 행 INSERT → SELECT 왕복 가능?
 *  - 3개 라인 주문 INSERT 후 snapshot 필드가 populated 되는가?
 *  - order_no 생성기가 50개 동시 INSERT에서 충돌 없는가? (AC2)
 *  - 인덱스가 존재하는가? (AC3)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=none"
})
class OrderSchemaIntegrationTest extends PostgresIntegrationSupport {

    @Autowired
    private EntityManager em;

    @Test
    void v7MigrationIsApplied() {
        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '7' AND success = TRUE
                """).getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    void ordersTableExistsWithConstraints() {
        // Check orders table has status check constraint
        @SuppressWarnings("unchecked")
        var constraints = em.createNativeQuery("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'orders'::regclass
                  AND conname = 'orders_status_check'
                """).getResultList();

        assertThat(constraints).isNotEmpty();
        String statusCheck = ((Object[]) constraints.get(0))[0].toString();
        assertThat(statusCheck).contains("CREATED");
        assertThat(statusCheck).contains("PAYMENT_PENDING");
        assertThat(statusCheck).contains("PAID");
    }

    @Test
    void orderItemsTableExistsWithSnapshotColumns() {
        // Check order_items table has snapshot columns
        @SuppressWarnings("unchecked")
        var columns = em.createNativeQuery("""
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_name = 'order_items'
                AND column_name IN ('product_name', 'option_name', 'unit_price')
                ORDER BY column_name
                """).getResultList();

        assertThat(columns).hasSize(3);
    }

    @Test
    void orderPriceSummariesTableExistsWithUniqueOrderId() {
        // Verify order_price_summaries has UNIQUE constraint on order_id
        @SuppressWarnings("unchecked")
        var constraints = em.createNativeQuery("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'order_price_summaries'::regclass
                  AND contype = 'u'
                """).getResultList();

        assertThat(constraints).isNotEmpty();
    }

    @Test
    void orderStatusHistoriesTableExistsWithChangedByKindCheck() {
        // Verify order_status_histories has CHECK constraint on changed_by_kind
        String checkDef = (String) em.createNativeQuery("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'order_status_histories'::regclass
                  AND conname = 'order_status_histories_changed_by_kind_check'
                """).getSingleResult();

        assertThat(checkDef).isNotNull();
        assertThat(checkDef).contains("USER");
        assertThat(checkDef).contains("ADMIN");
        assertThat(checkDef).contains("SYSTEM");
    }

    @Test
    void repositoryTest_InsertsOrderWithThreeLineItems_ReadsBack() {
        // Given: create test member and address
        Long memberId = ((Number) em.createNativeQuery("""
                INSERT INTO members (email, password_hash, name, phone, status, grade_id)
                VALUES ('test@example.com', '$2a$12$test', '테스트회원', '01012345678', 'ACTIVE',
                        (SELECT id FROM member_grades LIMIT 1))
                RETURNING id
                """).getSingleResult()).longValue();

        Long addressId = ((Number) em.createNativeQuery("""
                INSERT INTO member_addresses (member_id, recipient_name, phone,
                    zipcode, address_main, address_detail, is_default)
                VALUES (:memberId, '홍길동', '01012345678', '12345', '서울시 강남구', '101호', TRUE)
                RETURNING id
                """).setParameter("memberId", memberId).getSingleResult()).longValue();

        Long productId = ((Number) em.createNativeQuery(
                "SELECT id FROM products LIMIT 1"
        ).getSingleResult()).longValue();

        Long optionId = ((Number) em.createNativeQuery(
                "SELECT id FROM product_options LIMIT 1"
        ).getSingleResult()).longValue();

        // When: order + 3 line items INSERT
        Long orderId = ((Number) em.createNativeQuery("""
                INSERT INTO orders (member_id, delivery_address_id,
                    total_product_amount, discount_amount, point_used_amount,
                    delivery_fee, final_payment_amount, status)
                VALUES (:memberId, :addressId, 45000, 0, 0, 3000, 48000, 'CREATED')
                RETURNING id
                """)
                .setParameter("memberId", memberId)
                .setParameter("addressId", addressId)
                .getSingleResult()).longValue();

        // Insert 3 line items
        em.createNativeQuery("""
                INSERT INTO order_items (order_id, product_id, product_option_id,
                    product_name, option_name, unit_price, quantity, total_amount)
                VALUES (:orderId, :productId, :optionId, '테스트 상품', '50ml', 15000, 1, 15000)
                """).setParameter("orderId", orderId)
                .setParameter("productId", productId)
                .setParameter("optionId", optionId)
                .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO order_items (order_id, product_id, product_option_id,
                    product_name, option_name, unit_price, quantity, total_amount)
                VALUES (:orderId, :productId, :optionId, '테스트 상품', '100ml', 20000, 1, 20000)
                """).setParameter("orderId", orderId)
                .setParameter("productId", productId)
                .setParameter("optionId", optionId)
                .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO order_items (order_id, product_id, product_option_id,
                    product_name, option_name, unit_price, quantity, total_amount)
                VALUES (:orderId, :productId, :optionId, '테스트 상품2', '50ml', 10000, 1, 10000)
                """).setParameter("orderId", orderId)
                .setParameter("productId", productId)
                .setParameter("optionId", optionId)
                .executeUpdate();

        em.flush();
        em.clear();

        // Then: read back and verify
        Object[] order = (Object[]) em.createNativeQuery("""
                SELECT order_no, status, final_payment_amount, member_id
                FROM orders WHERE id = :id
                """).setParameter("id", orderId).getSingleResult();

        assertThat(order[0]).isNotNull();  // order_no was generated
        assertThat(order[0].toString()).startsWith("ORD");  // format check
        assertThat(order[1]).isEqualTo("CREATED");
        assertThat(((Number) order[2]).doubleValue()).isEqualTo(48000.00);

        // Verify snapshot fields in order_items
        @SuppressWarnings("unchecked")
        List<Object[]> items = em.createNativeQuery("""
                SELECT product_name, option_name, unit_price, quantity, total_amount
                FROM order_items WHERE order_id = :orderId
                ORDER BY total_amount
                """).setParameter("orderId", orderId).getResultList();

        assertThat(items).hasSize(3);
        assertThat(items.get(0)[0]).isEqualTo("테스트 상품2");
        assertThat(items.get(1)[0]).isEqualTo("테스트 상품");
        assertThat(items.get(2)[0]).isEqualTo("테스트 상품");
    }

    @Test
    void orderNoGenerator_NoCollisionsUnderConcurrentInserts() throws InterruptedException {
        // AC2: order_no generator never collides under 50 concurrent inserts
        // Given: create test member and address
        Long memberId = ((Number) em.createNativeQuery("""
                INSERT INTO members (email, password_hash, name, phone, status, grade_id)
                VALUES ('test2@example.com', '$2a$12$test', '테스트회원2', '01012345678', 'ACTIVE',
                        (SELECT id FROM member_grades LIMIT 1))
                RETURNING id
                """).getSingleResult()).longValue();

        Long addressId = ((Number) em.createNativeQuery("""
                INSERT INTO member_addresses (member_id, recipient_name, phone,
                    zipcode, address_main, address_detail, is_default)
                VALUES (:memberId, '김철수', '01012345678', '12345', '서울시 강남구', '102호', TRUE)
                RETURNING id
                """).setParameter("memberId", memberId).getSingleResult()).longValue();

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // When: 50 threads INSERT concurrently
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    // Each thread uses its own EntityManager
                    EntityManager em2 = em.getEntityManagerFactory().createEntityManager();
                    em2.getTransaction().begin();

                    em2.createNativeQuery("""
                            INSERT INTO orders (member_id, delivery_address_id,
                                total_product_amount, final_payment_amount, status)
                            VALUES (:memberId, :addressId, 10000, 10000, 'CREATED')
                            """)
                            .setParameter("memberId", memberId)
                            .setParameter("addressId", addressId)
                            .executeUpdate();

                    em2.getTransaction().commit();
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Then: all inserts succeed, no collisions
        assertThat(failureCount.get()).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(threadCount);

        // Verify all order_no values are unique
        @SuppressWarnings("unchecked")
        List<String> orderNos = em.createNativeQuery("""
                SELECT order_no FROM orders ORDER BY created_at DESC LIMIT 50
                """).getResultList();

        assertThat(orderNos).hasSize(threadCount);
        assertThat(orderNos).doesNotHaveDuplicates();
    }

    @Test
    void ac3_Index_MemberIdCreatedAtDesc_Exists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'orders' AND indexname = 'idx_orders_member_created'
                """).getResultList();

        assertThat(indexes).hasSize(1);
    }

    @Test
    void ac3_Index_StatusCreatedAt_Exists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'orders' AND indexname = 'idx_orders_status_created'
                """).getResultList();

        assertThat(indexes).hasSize(1);
    }

    @Test
    void ac3_Index_OrderNo_Unique_Exists() {
        // order_no UNIQUE is defined as column constraint, verify it exists
        @SuppressWarnings("unchecked")
        var constraints = em.createNativeQuery("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'orders'::regclass
                  AND contype = 'u'
                  AND conname LIKE '%order_no%'
                """).getResultList();

        assertThat(constraints).isNotEmpty();
    }
}
