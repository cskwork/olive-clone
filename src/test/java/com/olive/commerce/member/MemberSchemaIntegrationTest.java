package com.olive.commerce.member;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * OLV-010 Acceptance Criteria 검증:
 *  - V2 마이그레이션이 적용됐는가? (flyway_schema_history 행)
 *  - members / member_addresses / member_login_histories 한 행 INSERT → SELECT 왕복 가능?
 *  - members.email 조회에 인덱스가 걸리는가? (UNIQUE 제약이 자동 생성하는 b-tree)
 *  - 시드 등급 BRONZE/SILVER/GOLD 가 들어갔는가?
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class MemberSchemaIntegrationTest extends PostgresIntegrationSupport {

    @Autowired
    private EntityManager em;

    @Test
    void v2MigrationIsApplied() {
        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '2' AND success = TRUE
                """).getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    void seedGradesArePresentInOrder() {
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery("""
                SELECT name, sort_order FROM member_grades ORDER BY sort_order
                """).getResultList();

        assertThat(rows).hasSize(3);
        assertThat(((Object[]) rows.get(0))[0]).isEqualTo("BRONZE");
        assertThat(((Object[]) rows.get(1))[0]).isEqualTo("SILVER");
        assertThat(((Object[]) rows.get(2))[0]).isEqualTo("GOLD");
    }

    @Test
    void insertsAndReadsBackMemberAddressAndLoginHistory() {
        Long bronzeId = ((Number) em.createNativeQuery(
                "SELECT id FROM member_grades WHERE name = 'BRONZE'"
        ).getSingleResult()).longValue();

        Long memberId = ((Number) em.createNativeQuery("""
                INSERT INTO members (email, password_hash, name, phone, grade_id)
                VALUES (:email, :hash, :name, :phone, :gradeId)
                RETURNING id
                """)
                .setParameter("email", "alice@example.com")
                .setParameter("hash", "$2a$12$dummybcrypthashforintegrationtest..............")
                .setParameter("name", "Alice")
                .setParameter("phone", "010-1234-5678")
                .setParameter("gradeId", bronzeId)
                .getSingleResult()).longValue();

        Long addressId = ((Number) em.createNativeQuery("""
                INSERT INTO member_addresses
                    (member_id, recipient_name, phone, zipcode, address_main, address_detail, is_default)
                VALUES (:mid, :rn, :phone, :zip, :main, :detail, TRUE)
                RETURNING id
                """)
                .setParameter("mid", memberId)
                .setParameter("rn", "Alice")
                .setParameter("phone", "010-1234-5678")
                .setParameter("zip", "06236")
                .setParameter("main", "서울 강남구 테헤란로 123")
                .setParameter("detail", "4층")
                .getSingleResult()).longValue();

        Long loginHistoryId = ((Number) em.createNativeQuery("""
                INSERT INTO member_login_histories
                    (member_id, ip_address, user_agent, success, failure_reason)
                VALUES (:mid, :ip, :ua, TRUE, NULL)
                RETURNING id
                """)
                .setParameter("mid", memberId)
                .setParameter("ip", "127.0.0.1")
                .setParameter("ua", "JUnit/5")
                .getSingleResult()).longValue();

        em.flush();
        em.clear();

        Object[] member = (Object[]) em.createNativeQuery("""
                SELECT email, name, status, grade_id, created_at, updated_at
                FROM members WHERE id = :id
                """)
                .setParameter("id", memberId)
                .getSingleResult();
        assertThat(member[0]).isEqualTo("alice@example.com");
        assertThat(member[1]).isEqualTo("Alice");
        assertThat(member[2]).isEqualTo("ACTIVE");
        assertThat(((Number) member[3]).longValue()).isEqualTo(bronzeId);
        assertThat(member[4]).as("created_at populated by DEFAULT now()").isNotNull();
        assertThat(member[5]).as("updated_at populated by DEFAULT now()").isNotNull();

        Object[] address = (Object[]) em.createNativeQuery("""
                SELECT member_id, recipient_name, zipcode, address_main, is_default
                FROM member_addresses WHERE id = :id
                """)
                .setParameter("id", addressId)
                .getSingleResult();
        assertThat(((Number) address[0]).longValue()).isEqualTo(memberId);
        assertThat(address[1]).isEqualTo("Alice");
        assertThat(address[2]).isEqualTo("06236");
        assertThat(address[3]).isEqualTo("서울 강남구 테헤란로 123");
        assertThat((Boolean) address[4]).isTrue();

        Object[] history = (Object[]) em.createNativeQuery("""
                SELECT member_id, ip_address, user_agent, success, failure_reason
                FROM member_login_histories WHERE id = :id
                """)
                .setParameter("id", loginHistoryId)
                .getSingleResult();
        assertThat(((Number) history[0]).longValue()).isEqualTo(memberId);
        assertThat(history[1]).isEqualTo("127.0.0.1");
        assertThat(history[2]).isEqualTo("JUnit/5");
        assertThat((Boolean) history[3]).isTrue();
        assertThat(history[4]).isNull();
    }

    @Test
    void emailLookupIsIndexed() {
        // UNIQUE 제약이 b-tree 인덱스를 자동으로 만든다 — pg_indexes 에서 확인.
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'members' AND indexdef LIKE '%(email)%'
                """).getResultList();
        assertThat(indexes)
                .as("members.email 에 UNIQUE 인덱스가 존재해야 한다")
                .hasSize(1);
        assertThat(((String) indexes.get(0))).contains("UNIQUE");
    }

    @Test
    void refreshTokenAndGradeContractsAreSatisfied() {
        // OLV-005 리프레시 토큰 contract: token_hash CHAR(64), revoked_at NULL 가능.
        Long bronzeId = ((Number) em.createNativeQuery(
                "SELECT id FROM member_grades WHERE name = 'BRONZE'"
        ).getSingleResult()).longValue();

        Long memberId = ((Number) em.createNativeQuery("""
                INSERT INTO members (email, password_hash, name, grade_id)
                VALUES ('refresh-probe@example.com', 'x', 'Probe', :gradeId)
                RETURNING id
                """).setParameter("gradeId", bronzeId).getSingleResult()).longValue();

        em.createNativeQuery("""
                INSERT INTO member_refresh_tokens (member_id, token_hash, expires_at)
                VALUES (:mid, :hash, now() + INTERVAL '14 days')
                """)
                .setParameter("mid", memberId)
                .setParameter("hash", "a".repeat(64))
                .executeUpdate();

        Number active = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM member_refresh_tokens
                WHERE member_id = :mid AND revoked_at IS NULL
                """).setParameter("mid", memberId).getSingleResult();
        assertThat(active.intValue()).isEqualTo(1);

        // 등급 비율은 DECIMAL(5,2) — BRONZE 의 point_rate 1.00 그대로 보존되는지.
        BigDecimal pointRate = (BigDecimal) em.createNativeQuery(
                "SELECT point_rate FROM member_grades WHERE id = :id"
        ).setParameter("id", bronzeId).getSingleResult();
        assertThat(pointRate).isEqualByComparingTo("1.00");
    }
}
