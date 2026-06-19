package com.olive.commerce.inventory;

import com.olive.commerce.common.event.OutboxEventDrainer;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4 reconciliation: INVENTORY_COMMIT_FAILED outbox event drain → inventory commit + idempotency.
 */
@SpringBootTest
class InventoryCommitRetryIT extends PostgresIntegrationSupport {

    @Autowired
    private OutboxEventDrainer drainer;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate txTemplate;

    private Long testOrderId;
    private Long testOptionId;
    private Long testOutboxEventId;

    @BeforeEach
    void setUp() {
        txTemplate.executeWithoutResult(status -> {
            // 가장 작은 필요 테이블만 정리 (payments/orders FK cascade 포함)
            jdbcTemplate.execute("""
                TRUNCATE outbox_events, inventory_reservations, inventory_histories,
                          inventories, orders, member_addresses, members,
                          brands, products, product_options
                RESTART IDENTITY CASCADE
                """);

            // member_grades seed 재삽입 (truncate CASCADE 로 지워질 수 있으므로)
            jdbcTemplate.update("""
                INSERT INTO member_grades (name, discount_rate, point_rate, benefit_description, sort_order)
                VALUES ('BRONZE', 0.00, 1.00, '기본 등급', 1)
                ON CONFLICT (name) DO NOTHING
                """);

            long gradeId = jdbcTemplate.queryForObject(
                "SELECT id FROM member_grades WHERE name = 'BRONZE'", Long.class);

            long memberId = jdbcTemplate.queryForObject("""
                INSERT INTO members (email, password_hash, name, phone, status, grade_id)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                RETURNING id
                """, Long.class,
                "inv-retry-test@example.com", "$2a$12$test", "재고테스트", "01012345678", gradeId);

            long addressId = jdbcTemplate.queryForObject("""
                INSERT INTO member_addresses (member_id, recipient_name, phone, zipcode, address_main, address_detail, is_default)
                VALUES (?, '홍길동', '01012345678', '12345', '서울시 강남구', '101호', true)
                RETURNING id
                """, Long.class, memberId);

            long brandId = jdbcTemplate.queryForObject("""
                INSERT INTO brands (name, slug, logo_url, status)
                VALUES ('Test Brand', 'test-brand', 'https://s3.local/img.png', 'ACTIVE')
                RETURNING id
                """, Long.class);

            long productId = jdbcTemplate.queryForObject("""
                INSERT INTO products (brand_id, name, description, status, base_price, sale_price)
                VALUES (?, '테스트 상품', '설명', 'ON_SALE', 10000, 10000)
                RETURNING id
                """, Long.class, brandId);

            testOptionId = jdbcTemplate.queryForObject("""
                INSERT INTO product_options (product_id, option_name, option_price, status)
                VALUES (?, '기본', 10000, 'ON_SALE')
                RETURNING id
                """, Long.class, productId);

            // 재고: total=100, reserved=1 (HELD 상태와 일치)
            jdbcTemplate.update("""
                INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
                VALUES (?, 100, 1)
                """, testOptionId);

            // 주문 (PAID 상태로 삽입 — order_no는 트리거가 자동 생성)
            testOrderId = jdbcTemplate.queryForObject("""
                INSERT INTO orders (member_id, delivery_address_id, status,
                                   total_product_amount, discount_amount, point_used_amount,
                                   delivery_fee, final_payment_amount)
                VALUES (?, ?, 'PAID', 10000, 0, 0, 0, 10000)
                RETURNING id
                """, Long.class, memberId, addressId);

            String testOrderNo = jdbcTemplate.queryForObject(
                "SELECT order_no FROM orders WHERE id = ?", String.class, testOrderId);

            // 재고 예약 (HELD 상태 — 커밋 실패를 시뮬레이션)
            jdbcTemplate.update("""
                INSERT INTO inventory_reservations (order_id, product_option_id, quantity, status, expires_at)
                VALUES (?, ?, 1, 'HELD', ?)
                """, testOrderId, testOptionId, Timestamp.from(Instant.now().plusSeconds(900)));

            // INVENTORY_COMMIT_FAILED outbox 이벤트 삽입
            String payload = String.format(
                "{\"orderId\":%d,\"orderNo\":\"%s\",\"paymentId\":99,\"reason\":\"mock commit failure\"}",
                testOrderId, testOrderNo);
            testOutboxEventId = jdbcTemplate.queryForObject("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload_json, status, attempt_count, dlq)
                VALUES ('PAYMENT', ?, 'INVENTORY_COMMIT_FAILED', ?, 'PENDING', 0, false)
                RETURNING id
                """, Long.class, testOrderId, payload);
        });
    }

    @AfterEach
    void tearDown() {
        txTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("""
                TRUNCATE outbox_events, inventory_reservations, inventory_histories,
                          inventories, orders, member_addresses, members,
                          brands, products, product_options
                RESTART IDENTITY CASCADE
                """);
        });
    }

    @Test
    @DisplayName("A4: INVENTORY_COMMIT_FAILED 이벤트 드레인 시 재고가 COMMITTED로 전이되고 이벤트는 DONE")
    void drainInventoryCommitFailed_commitsReservationAndMarksEventDone() {
        // When: 드레이너 1회 실행
        int processed = drainer.drainOnce();

        // Then: 이벤트 1건 처리됨
        assertThat(processed).isGreaterThanOrEqualTo(1);

        // Then: outbox 이벤트가 DONE으로 전이됨
        OutboxEvent event = outboxEventRepository.findById(testOutboxEventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DONE);

        // Then: 재고 예약이 COMMITTED로 전이됨
        var reservations = reservationRepository.findByOrderId(testOrderId);
        assertThat(reservations).isNotEmpty();
        assertThat(reservations).allMatch(r ->
            r.getStatus() == InventoryReservation.ReservationStatus.COMMITTED);
    }

    @Test
    @DisplayName("A4: 드레인 두 번째 실행은 no-op (멱등성) — 이미 DONE이므로 재처리 없음")
    void drainTwice_secondDrainIsNoOp() {
        // Given: 첫 번째 드레인으로 커밋 완료
        drainer.drainOnce();

        OutboxEvent afterFirst = outboxEventRepository.findById(testOutboxEventId).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DONE);

        // When: 두 번째 드레인 실행 — DONE 상태이므로 findPendingBatch에서 제외됨
        int secondProcessed = drainer.drainOnce();

        // Then: 동일 이벤트 재처리 없음
        OutboxEvent afterSecond = outboxEventRepository.findById(testOutboxEventId).orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DONE);
        assertThat(afterSecond.getAttemptCount()).isEqualTo(0); // 실패 없음

        // Then: 재고 예약은 여전히 COMMITTED (이중 커밋 없음)
        var reservations = reservationRepository.findByOrderId(testOrderId);
        assertThat(reservations).allMatch(r ->
            r.getStatus() == InventoryReservation.ReservationStatus.COMMITTED);
    }
}
