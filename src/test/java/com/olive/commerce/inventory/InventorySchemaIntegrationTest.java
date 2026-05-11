package com.olive.commerce.inventory;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * OLV-030 Acceptance Criteria 검증:
 *  - V4 마이그레이션이 적용됐는가? (flyway_schema_history 행)
 *  - AC1: inventories.available_quantity 가 GENERATED 컬럼이며, total/reserved 변경 시 자동 갱신되는가?
 *  - AC2: (order_id, product_option_id) 중복 삽입 시 UNIQUE 위반인가?
 *  - AC3: Repository 테스트: stock 100, reserve 30 → available 70; commit 30 → total 70, reserved 0, available 70.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class InventorySchemaIntegrationTest extends PostgresIntegrationSupport {

    @Autowired
    private EntityManager em;

    @Test
    void v4MigrationIsApplied() {
        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '4' AND success = TRUE
                """).getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    void inventoriesTableHasAllColumnsAndConstraints() {
        // product_options 테이블에서 demo option id 가져오기 (V3 시드 데이터)
        Long optionId = ((Number) em.createNativeQuery(
                "SELECT id FROM product_options LIMIT 1"
        ).getSingleResult()).longValue();

        // INSERT
        Long inventoryId = ((Number) em.createNativeQuery("""
                INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
                VALUES (:optionId, 100, 0)
                RETURNING id
                """)
                .setParameter("optionId", optionId)
                .getSingleResult()).longValue();

        em.flush();
        em.clear();

        // SELECT: available_quantity 가 자동 계산됐는지 확인
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT id, product_option_id, total_quantity, reserved_quantity, available_quantity
                FROM inventories WHERE id = :id
                """)
                .setParameter("id", inventoryId)
                .getSingleResult();

        assertThat(((Number) row[0]).longValue()).isEqualTo(inventoryId);
        assertThat(((Number) row[1]).longValue()).isEqualTo(optionId);
        assertThat(((Number) row[2]).intValue()).isEqualTo(100);
        assertThat(((Number) row[3]).intValue()).isEqualTo(0);
        assertThat(((Number) row[4]).intValue()).as("available = total - reserved = 100 - 0").isEqualTo(100);
    }

    @Test
    void availableQuantityIsGeneratedAndUpdatesAutomatically_AC1() {
        Long optionId = ((Number) em.createNativeQuery(
                "SELECT id FROM product_options LIMIT 1"
        ).getSingleResult()).longValue();

        Long inventoryId = ((Number) em.createNativeQuery("""
                INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
                VALUES (:optionId, 100, 0)
                RETURNING id
                """)
                .setParameter("optionId", optionId)
                .getSingleResult()).longValue();

        em.flush();

        // total=100, reserved=0 → available=100
        Integer available1 = ((Number) em.createNativeQuery(
                "SELECT available_quantity FROM inventories WHERE id = :id"
        ).setParameter("id", inventoryId).getSingleResult()).intValue();
        assertThat(available1).isEqualTo(100);

        // reserved를 30으로 변경 → available 자동으로 70으로 변경
        em.createNativeQuery("""
                UPDATE inventories SET reserved_quantity = 30 WHERE id = :id
                """).setParameter("id", inventoryId).executeUpdate();
        em.flush();

        Integer available2 = ((Number) em.createNativeQuery(
                "SELECT available_quantity FROM inventories WHERE id = :id"
        ).setParameter("id", inventoryId).getSingleResult()).intValue();
        assertThat(available2).as("available = total - reserved = 100 - 30").isEqualTo(70);

        // total을 70으로 변경 → available 자동으로 40으로 변경
        em.createNativeQuery("""
                UPDATE inventories SET total_quantity = 70 WHERE id = :id
                """).setParameter("id", inventoryId).executeUpdate();
        em.flush();

        Integer available3 = ((Number) em.createNativeQuery(
                "SELECT available_quantity FROM inventories WHERE id = :id"
        ).setParameter("id", inventoryId).getSingleResult()).intValue();
        assertThat(available3).as("available = total - reserved = 70 - 30").isEqualTo(40);
    }

    @Test
    void checkConstraintsEnforceNonNegativeAndTotalGteReserved() {
        Long optionId = ((Number) em.createNativeQuery(
                "SELECT id FROM product_options LIMIT 1"
        ).getSingleResult()).longValue();

        // total_quantity >= 0 CHECK 제약
        assertThatThrownBy(() -> {
            em.createNativeQuery("""
                    INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
                    VALUES (:optionId, -1, 0)
                    """).setParameter("optionId", optionId).executeUpdate();
        }).isInstanceOf(Exception.class);

        // reserved_quantity >= 0 CHECK 제약
        assertThatThrownBy(() -> {
            em.createNativeQuery("""
                    INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
                    VALUES (:optionId, 100, -1)
                    """).setParameter("optionId", optionId).executeUpdate();
        }).isInstanceOf(Exception.class);

        // total_quantity >= reserved_quantity CHECK 제약
        assertThatThrownBy(() -> {
            em.createNativeQuery("""
                    INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
                    VALUES (:optionId, 50, 100)
                    """).setParameter("optionId", optionId).executeUpdate();
        }).isInstanceOf(Exception.class);
    }

    @Test
    void inventoryHistoriesAppendOnlyWithCorrectEnumValues() {
        Long optionId = ((Number) em.createNativeQuery(
                "SELECT id FROM product_options LIMIT 1"
        ).getSingleResult()).longValue();

        // STOCK_IN 기록
        em.createNativeQuery("""
                INSERT INTO inventory_histories (product_option_id, change_type, quantity_delta, reason)
                VALUES (:optionId, 'STOCK_IN', 100, '입고 #PO-123')
                """).setParameter("optionId", optionId).executeUpdate();

        // RESERVE 기록
        em.createNativeQuery("""
                INSERT INTO inventory_histories (product_option_id, change_type, quantity_delta, reason, order_id)
                VALUES (:optionId, 'RESERVE', -30, '주문 예약', 12345)
                """).setParameter("optionId", optionId).executeUpdate();

        em.flush();

        @SuppressWarnings("unchecked")
        List<Object[]> histories = em.createNativeQuery("""
                SELECT change_type, quantity_delta, reason, order_id, created_at
                FROM inventory_histories WHERE product_option_id = :optionId
                ORDER BY created_at
                """).setParameter("optionId", optionId).getResultList();

        assertThat(histories).hasSize(2);
        assertThat(histories.get(0)[0]).isEqualTo("STOCK_IN");
        assertThat(((Number) histories.get(0)[1]).intValue()).isEqualTo(100);
        assertThat(histories.get(0)[2]).isEqualTo("입고 #PO-123");
        assertThat(histories.get(0)[3]).isNull();
        assertThat(histories.get(0)[4]).as("created_at populated by DEFAULT now()").isNotNull();

        assertThat(histories.get(1)[0]).isEqualTo("RESERVE");
        assertThat(((Number) histories.get(1)[1]).intValue()).isEqualTo(-30);
        assertThat(histories.get(1)[2]).isEqualTo("주문 예약");
        assertThat(((Number) histories.get(1)[3]).longValue()).isEqualTo(12345L);
    }

    @Test
    void changeTypeEnumCheckRejectsInvalidValues() {
        Long optionId = ((Number) em.createNativeQuery(
                "SELECT id FROM product_options LIMIT 1"
        ).getSingleResult()).longValue();

        assertThatThrownBy(() -> {
            em.createNativeQuery("""
                    INSERT INTO inventory_histories (product_option_id, change_type, quantity_delta, reason)
                    VALUES (:optionId, 'INVALID_TYPE', 10, 'test')
                    """).setParameter("optionId", optionId).executeUpdate();
        }).isInstanceOf(Exception.class);
    }

    @Test
    void inventoryReservationsUniqueConstraintOnOrderAndOption_AC2() {
        Long optionId = ((Number) em.createNativeQuery(
                "SELECT id FROM product_options LIMIT 1"
        ).getSingleResult()).longValue();

        // 첫 번째 예약 성공
        em.createNativeQuery("""
                INSERT INTO inventory_reservations (order_id, product_option_id, quantity, status, expires_at)
                VALUES (10001, :optionId, 1, 'HELD', now() + INTERVAL '15 minutes')
                """).setParameter("optionId", optionId).executeUpdate();

        em.flush();

        // 같은 (order_id, product_option_id) 조합으로 두 번째 삽입 시도 → UNIQUE 위반
        assertThatThrownBy(() -> {
            em.createNativeQuery("""
                    INSERT INTO inventory_reservations (order_id, product_option_id, quantity, status, expires_at)
                    VALUES (10001, :optionId, 1, 'HELD', now() + INTERVAL '15 minutes')
                    """).setParameter("optionId", optionId).executeUpdate();
        }).isInstanceOf(Exception.class);
    }

    @Test
    void reservationStatusEnumAndFinalizedAtConsistency() {
        Long optionId = ((Number) em.createNativeQuery(
                "SELECT id FROM product_options LIMIT 1"
        ).getSingleResult()).longValue();

        // HELD 상태 생성 (finalized_at NULL)
        Long reservationId = ((Number) em.createNativeQuery("""
                INSERT INTO inventory_reservations (order_id, product_option_id, quantity, status, expires_at)
                VALUES (10002, :optionId, 2, 'HELD', now() + INTERVAL '15 minutes')
                RETURNING id
                """).setParameter("optionId", optionId).getSingleResult()).longValue();

        em.flush();

        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT status, finalized_at FROM inventory_reservations WHERE id = :id"
        ).setParameter("id", reservationId).getSingleResult();
        assertThat(row[0]).isEqualTo("HELD");
        assertThat(row[1]).isNull();

        // COMMITTED로 변경 (finalized_at 설정)
        em.createNativeQuery("""
                UPDATE inventory_reservations
                SET status = 'COMMITTED', finalized_at = now()
                WHERE id = :id
                """).setParameter("id", reservationId).executeUpdate();
        em.flush();

        Object[] updatedRow = (Object[]) em.createNativeQuery(
                "SELECT status, finalized_at FROM inventory_reservations WHERE id = :id"
        ).setParameter("id", reservationId).getSingleResult();
        assertThat(updatedRow[0]).isEqualTo("COMMITTED");
        assertThat(updatedRow[1]).as("finalized_at set when status = COMMITTED").isNotNull();

        // CHECK 제약: HELD 상태에서 finalized_at 설정 불가
        assertThatThrownBy(() -> {
            em.createNativeQuery("""
                    INSERT INTO inventory_reservations (order_id, product_option_id, quantity, status, expires_at, finalized_at)
                    VALUES (10003, :optionId, 1, 'HELD', now() + INTERVAL '15 minutes', now())
                    """).setParameter("optionId", optionId).executeUpdate();
        }).isInstanceOf(Exception.class);
    }

    @Test
    void reserveThenCommitFlow_AC3() {
        Long optionId = ((Number) em.createNativeQuery(
                "SELECT id FROM product_options LIMIT 1"
        ).getSingleResult()).longValue();

        // 초기 상태: total=100, reserved=0
        Long inventoryId = ((Number) em.createNativeQuery("""
                INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
                VALUES (:optionId, 100, 0)
                RETURNING id
                """)
                .setParameter("optionId", optionId)
                .getSingleResult()).longValue();

        em.flush();

        // 1. Reserve 30 → reserved=30, available=70
        em.createNativeQuery("""
                UPDATE inventories SET reserved_quantity = 30 WHERE id = :id
                """).setParameter("id", inventoryId).executeUpdate();

        // history 기록
        em.createNativeQuery("""
                INSERT INTO inventory_histories (product_option_id, change_type, quantity_delta, reason, order_id)
                VALUES (:optionId, 'RESERVE', -30, '주문 예약', 10004)
                """).setParameter("optionId", optionId).executeUpdate();

        // reservation 생성
        em.createNativeQuery("""
                INSERT INTO inventory_reservations (order_id, product_option_id, quantity, status, expires_at)
                VALUES (10004, :optionId, 30, 'HELD', now() + INTERVAL '15 minutes')
                """).setParameter("optionId", optionId).executeUpdate();

        em.flush();

        Object[] afterReserve = (Object[]) em.createNativeQuery(
                "SELECT total_quantity, reserved_quantity, available_quantity FROM inventories WHERE id = :id"
        ).setParameter("id", inventoryId).getSingleResult();
        assertThat(((Number) afterReserve[0]).intValue()).isEqualTo(100);
        assertThat(((Number) afterReserve[1]).intValue()).isEqualTo(30);
        assertThat(((Number) afterReserve[2]).intValue()).as("available = 100 - 30 = 70").isEqualTo(70);

        // 2. Commit 30 → total=70, reserved=0, available=70
        em.createNativeQuery("""
                UPDATE inventories
                SET total_quantity = total_quantity - 30,
                    reserved_quantity = reserved_quantity - 30
                WHERE id = :id
                """).setParameter("id", inventoryId).executeUpdate();

        // history 기록
        em.createNativeQuery("""
                INSERT INTO inventory_histories (product_option_id, change_type, quantity_delta, reason, order_id)
                VALUES (:optionId, 'COMMIT', -30, '결제 승인', 10004)
                """).setParameter("optionId", optionId).executeUpdate();

        // reservation 상태 변경
        em.createNativeQuery("""
                UPDATE inventory_reservations
                SET status = 'COMMITTED', finalized_at = now()
                WHERE order_id = 10004 AND product_option_id = :optionId
                """).setParameter("optionId", optionId).executeUpdate();

        em.flush();

        Object[] afterCommit = (Object[]) em.createNativeQuery(
                "SELECT total_quantity, reserved_quantity, available_quantity FROM inventories WHERE id = :id"
        ).setParameter("id", inventoryId).getSingleResult();
        assertThat(((Number) afterCommit[0]).intValue()).as("total = 100 - 30").isEqualTo(70);
        assertThat(((Number) afterCommit[1]).intValue()).as("reserved = 30 - 30").isEqualTo(0);
        assertThat(((Number) afterCommit[2]).intValue()).as("available = 70 - 0").isEqualTo(70);

        // history 확인
        @SuppressWarnings("unchecked")
        List<Object[]> histories = em.createNativeQuery("""
                SELECT change_type, quantity_delta FROM inventory_histories
                WHERE product_option_id = :optionId AND order_id = 10004
                ORDER BY created_at
                """).setParameter("optionId", optionId).getResultList();
        assertThat(histories).hasSize(2);
        assertThat(histories.get(0)[0]).isEqualTo("RESERVE");
        assertThat(histories.get(1)[0]).isEqualTo("COMMIT");
    }

    @Test
    void indexesExistForBatchScanAndLookups() {
        @SuppressWarnings("unchecked")
        List<String> indexes = em.createNativeQuery("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN ('inventories', 'inventory_histories', 'inventory_reservations')
                ORDER BY tablename, indexname
                """).getResultList();

        assertThat(indexes).as("inventory 관련 인덱스 최소 6개 이상").hasSizeGreaterThanOrEqualTo(6);

        // 주요 인덱스 존재 확인
        String indexList = String.join(", ", indexes);
        assertThat(indexList).contains("idx_inventories_product_option_id");
        assertThat(indexList).contains("idx_inventory_histories_product_option_id");
        assertThat(indexList).contains("idx_inventory_histories_created_at");
        assertThat(indexList).contains("uniq_inventory_reservations_order_option");
        assertThat(indexList).contains("idx_inventory_reservations_status_expires");
        assertThat(indexList).contains("idx_inventory_reservations_product_option_id");
    }
}
