package com.olive.commerce.promotion;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * OLV-050 Acceptance Criteria 검증:
 *  - V6 마이그레이션이 적용됐는가?
 *  - coupons / member_coupons / promotions / promotion_products / points / point_histories
 *    INSERT → SELECT 왕복 가능?
 *  - member_coupons(member_id, status) 인덱스가 존재하는가?
 *  - point_histories(member_id, available_at, expires_at) 인덱스가 존재하는가?
 *  - points.balance 트리거가 동작하는가? (point_histories INSERT → balance 증가)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class PromotionSchemaIntegrationTest extends PostgresIntegrationSupport {

    @Autowired
    private EntityManager em;

    @Test
    void v6MigrationIsApplied() {
        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '6' AND success = TRUE
                """).getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    void insertsAndReadsBackCoupon() {
        Long couponId = ((Number) em.createNativeQuery("""
                INSERT INTO coupons (name, discount_type, discount_value, min_order_amount,
                                     started_at, ended_at, max_issue_count)
                VALUES (:name, :type, :value, :minAmount, :started, :ended, :maxCount)
                RETURNING id
                """)
                .setParameter("name", "테스트 쿠폰")
                .setParameter("type", "FIXED_AMOUNT")
                .setParameter("value", new BigDecimal("5000"))
                .setParameter("minAmount", new BigDecimal("10000"))
                .setParameter("started", LocalDateTime.now())
                .setParameter("ended", LocalDateTime.now().plusDays(30))
                .setParameter("maxCount", 1000)
                .getSingleResult()).longValue();

        em.flush();
        em.clear();

        Object[] coupon = (Object[]) em.createNativeQuery("""
                SELECT name, discount_type, discount_value, min_order_amount,
                       status, issued_count, max_issue_count
                FROM coupons WHERE id = :id
                """)
                .setParameter("id", couponId)
                .getSingleResult();

        assertThat(coupon[0]).isEqualTo("테스트 쿠폰");
        assertThat(coupon[1]).isEqualTo("FIXED_AMOUNT");
        assertThat((BigDecimal) coupon[2]).isEqualByComparingTo("5000");
        assertThat((BigDecimal) coupon[3]).isEqualByComparingTo("10000");
        assertThat(coupon[4]).isEqualTo("ACTIVE");
        assertThat(coupon[5]).isEqualTo(0);
        assertThat(coupon[6]).isEqualTo(1000);
    }

    @Test
    void insertsAndReadsBackMemberCoupon() {
        // 먼저 members 테이블에서 기존 회원 ID 조회 또는 생성
        Number memberResult = (Number) em.createNativeQuery("""
                SELECT id FROM members LIMIT 1
                """).getResultList().stream().findFirst()
                .map(r -> ((Number) r).longValue())
                .orElseGet(() -> {
                    Number gradeId = (Number) em.createNativeQuery(
                            "SELECT id FROM member_grades WHERE name = 'BRONZE'"
                    ).getSingleResult();
                    return ((Number) em.createNativeQuery("""
                            INSERT INTO members (email, password_hash, name, grade_id)
                            VALUES ('test-promotion@example.com', 'x', 'Test', :gradeId)
                            RETURNING id
                            """).setParameter("gradeId", gradeId)
                            .getSingleResult()).longValue();
                });
        Long memberId = memberResult.longValue();

        // 쿠폰 생성
        Long couponId = ((Number) em.createNativeQuery("""
                INSERT INTO coupons (name, discount_type, discount_value, started_at, ended_at)
                VALUES (:name, :type, :value, now(), now() + INTERVAL '30 days')
                RETURNING id
                """)
                .setParameter("name", "회원 발급 쿠폰")
                .setParameter("type", "PERCENTAGE")
                .setParameter("value", new BigDecimal("10"))
                .getSingleResult()).longValue();

        // 회원 쿠폰 발급
        Long memberCouponId = ((Number) em.createNativeQuery("""
                INSERT INTO member_coupons (member_id, coupon_id, status, expires_at)
                VALUES (:mid, :cid, 'ISSUED', now() + INTERVAL '30 days')
                RETURNING id
                """)
                .setParameter("mid", memberId)
                .setParameter("cid", couponId)
                .getSingleResult()).longValue();

        em.flush();
        em.clear();

        Object[] memberCoupon = (Object[]) em.createNativeQuery("""
                SELECT member_id, coupon_id, status, issued_at, used_at, expires_at
                FROM member_coupons WHERE id = :id
                """)
                .setParameter("id", memberCouponId)
                .getSingleResult();

        assertThat(((Number) memberCoupon[0]).longValue()).isEqualTo(memberId);
        assertThat(((Number) memberCoupon[1]).longValue()).isEqualTo(couponId);
        assertThat(memberCoupon[2]).isEqualTo("ISSUED");
        assertThat(memberCoupon[3]).isNotNull();
        assertThat(memberCoupon[4]).isNull();
        assertThat(memberCoupon[5]).isNotNull();
    }

    @Test
    void insertsAndReadsBackPromotionAndProducts() {
        // 프로모션 생성 (PostgreSQL은 문자열을 JSONB로 자동 변환)
        Long promotionId = ((Number) em.createNativeQuery("""
                INSERT INTO promotions (name, type, started_at, ended_at, discount_rule_json)
                VALUES (:name, :type, now(), now() + INTERVAL '30 days', CAST(:rule AS JSONB))
                RETURNING id
                """)
                .setParameter("name", "테스트 기획전")
                .setParameter("type", "EVENT")
                .setParameter("rule", "{\"discount_percent\": 20}")
                .getSingleResult()).longValue();

        // 기존 상품 ID 조회
        Number productId = (Number) em.createNativeQuery("""
                SELECT id FROM products LIMIT 1
                """).getSingleResult();

        // 프로모션-상품 연결
        em.createNativeQuery("""
                INSERT INTO promotion_products (promotion_id, product_id)
                VALUES (:pid, :prid)
                """)
                .setParameter("pid", promotionId)
                .setParameter("prid", productId)
                .executeUpdate();

        em.flush();
        em.clear();

        // 프로모션 조회
        Object[] promotion = (Object[]) em.createNativeQuery("""
                SELECT name, type, discount_rule_json
                FROM promotions WHERE id = :id
                """)
                .setParameter("id", promotionId)
                .getSingleResult();

        assertThat(promotion[0]).isEqualTo("테스트 기획전");
        assertThat(promotion[1]).isEqualTo("EVENT");
        assertThat(promotion[2]).toString().contains("discount_percent");

        // 연결된 상품 조회
        Number productCount = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM promotion_products WHERE promotion_id = :pid
                """)
                .setParameter("pid", promotionId)
                .getSingleResult();

        assertThat(productCount.intValue()).isEqualTo(1);
    }

    @Test
    void insertsAndReadsBackPointsAndHistory() {
        // 회원 ID 조회 또는 생성
        Number memberResult = (Number) em.createNativeQuery("""
                SELECT id FROM members LIMIT 1
                """).getResultList().stream().findFirst()
                .map(r -> ((Number) r).longValue())
                .orElseGet(() -> {
                    Number gradeId = (Number) em.createNativeQuery(
                            "SELECT id FROM member_grades WHERE name = 'BRONZE'"
                    ).getSingleResult();
                    return ((Number) em.createNativeQuery("""
                            INSERT INTO members (email, password_hash, name, grade_id)
                            VALUES ('test-points@example.com', 'x', 'Points Test', :gradeId)
                            RETURNING id
                            """).setParameter("gradeId", gradeId)
                            .getSingleResult()).longValue();
                });
        Long memberId = memberResult.longValue();

        // 포인트 원장 기록 (적립)
        em.createNativeQuery("""
                INSERT INTO point_histories (member_id, change_type, amount, reason,
                                            available_at, expires_at)
                VALUES (:mid, 'EARN', :amount, :reason, now(), NULL)
                """)
                .setParameter("mid", memberId)
                .setParameter("amount", new BigDecimal("1000"))
                .setParameter("reason", "테스트 적립")
                .executeUpdate();

        em.flush();
        em.clear();

        // 포인트 잔액 확인 (트리거로 자동 계산되어야 함)
        Object[] points = (Object[]) em.createNativeQuery("""
                SELECT member_id, balance
                FROM points WHERE member_id = :mid
                """)
                .setParameter("mid", memberId)
                .getSingleResult();

        assertThat(((Number) points[0]).longValue()).isEqualTo(memberId);
        assertThat((BigDecimal) points[1]).isEqualByComparingTo("1000");

        // 원장 기록 확인
        @SuppressWarnings("unchecked")
        List<Object[]> histories = em.createNativeQuery("""
                SELECT member_id, change_type, amount, reason
                FROM point_histories WHERE member_id = :mid
                """)
                .setParameter("mid", memberId)
                .getResultList();

        assertThat(histories).hasSize(1);
        assertThat(((Number) histories.get(0)[0]).longValue()).isEqualTo(memberId);
        assertThat(histories.get(0)[1]).isEqualTo("EARN");
        assertThat((BigDecimal) histories.get(0)[2]).isEqualByComparingTo("1000");
        assertThat(histories.get(0)[3]).isEqualTo("테스트 적립");
    }

    @Test
    void memberCouponStatusIndexExists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'member_coupons'
                  AND indexname LIKE '%member%'
                  AND indexdef LIKE '%status%'
                """).getResultList();

        assertThat(indexes)
                .as("member_coupons(member_id, status) 인덱스가 존재해야 한다")
                .isNotEmpty();
    }

    @Test
    void pointHistoriesIndexExists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'point_histories'
                  AND indexname LIKE '%member%'
                  AND indexdef LIKE '%available%'
                  AND indexdef LIKE '%expires%'
                """).getResultList();

        assertThat(indexes)
                .as("point_histories(member_id, available_at, expires_at) 인덱스가 존재해야 한다")
                .isNotEmpty();
    }

    @Test
    void pointsBalanceTriggerUpdatesOnHistoryInsert() {
        // 회원 ID 조회 또는 생성
        Number memberResult = (Number) em.createNativeQuery("""
                SELECT id FROM members LIMIT 1
                """).getResultList().stream().findFirst()
                .map(r -> ((Number) r).longValue())
                .orElseGet(() -> {
                    Number gradeId = (Number) em.createNativeQuery(
                            "SELECT id FROM member_grades WHERE name = 'BRONZE'"
                    ).getSingleResult();
                    return ((Number) em.createNativeQuery("""
                            INSERT INTO members (email, password_hash, name, grade_id)
                            VALUES ('test-trigger@example.com', 'x', 'Trigger Test', :gradeId)
                            RETURNING id
                            """).setParameter("gradeId", gradeId)
                            .getSingleResult()).longValue();
                });
        Long memberId = memberResult.longValue();

        // 초기 적립
        em.createNativeQuery("""
                INSERT INTO point_histories (member_id, change_type, amount, reason, available_at)
                VALUES (:mid, 'EARN', 500, '초기 적립', now())
                """)
                .setParameter("mid", memberId)
                .executeUpdate();

        em.flush();
        em.clear();

        BigDecimal balance1 = (BigDecimal) em.createNativeQuery("""
                SELECT balance FROM points WHERE member_id = :mid
                """)
                .setParameter("mid", memberId)
                .getSingleResult();

        assertThat(balance1).isEqualByComparingTo("500");

        // 추가 적립
        em.createNativeQuery("""
                INSERT INTO point_histories (member_id, change_type, amount, reason, available_at)
                VALUES (:mid, 'EARN', 300, '추가 적립', now())
                """)
                .setParameter("mid", memberId)
                .executeUpdate();

        em.flush();
        em.clear();

        BigDecimal balance2 = (BigDecimal) em.createNativeQuery("""
                SELECT balance FROM points WHERE member_id = :mid
                """)
                .setParameter("mid", memberId)
                .getSingleResult();

        assertThat(balance2).isEqualByComparingTo("800");

        // 포인트 사용
        em.createNativeQuery("""
                INSERT INTO point_histories (member_id, change_type, amount, reason, available_at)
                VALUES (:mid, 'USE', 200, '포인트 사용', now())
                """)
                .setParameter("mid", memberId)
                .executeUpdate();

        em.flush();
        em.clear();

        BigDecimal balance3 = (BigDecimal) em.createNativeQuery("""
                SELECT balance FROM points WHERE member_id = :mid
                """)
                .setParameter("mid", memberId)
                .getSingleResult();

        assertThat(balance3).isEqualByComparingTo("600");
    }

    @Test
    void seedCouponsArePresent() {
        @SuppressWarnings("unchecked")
        List<Object[]> coupons = em.createNativeQuery("""
                SELECT name, discount_type, discount_value
                FROM coupons
                ORDER BY id
                """).getResultList();

        assertThat(coupons).hasSizeGreaterThanOrEqualTo(4);

        // 시드 데이터 확인
        assertThat(coupons.stream()
                .map(c -> ((String) c[0]))
                .anyMatch(name -> name.contains("신규 회원 3000원"))).isTrue();

        assertThat(coupons.stream()
                .map(c -> ((String) c[0]))
                .anyMatch(name -> name.contains("10% 할인"))).isTrue();
    }

    @Test
    void seedPromotionExists() {
        @SuppressWarnings("unchecked")
        List<Object[]> promotions = em.createNativeQuery("""
                SELECT name, type
                FROM promotions
                """).getResultList();

        assertThat(promotions).isNotEmpty();
        assertThat(promotions.get(0)[0]).isEqualTo("여름 선크림 특가");
        assertThat(promotions.get(0)[1]).isEqualTo("TIME_DEAL");
    }
}
