package com.olive.commerce.review;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * V11__review.sql 마이그레이션 적용 검증.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class ReviewSchemaIntegrationTest extends PostgresIntegrationSupport {

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("V11__review.sql 마이그레이션 적용 확인")
    void v11MigrationIsApplied() {
        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '11' AND success = TRUE
                """).getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("reviews 테이블 존재 및 제약조건 확인")
    void reviewsTableExistsWithConstraints() {
        // reviews 테이블 존재 확인
        Number count = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'reviews'"
        ).getSingleResult();
        assertThat(count.intValue()).isGreaterThan(0);

        // rating CHECK 제약 확인
        String ratingCheck = (String) em.createNativeQuery("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'reviews'::regclass
                  AND conname LIKE '%rating%'
                """).getSingleResult();

        assertThat(ratingCheck).isNotNull();
        assertThat(ratingCheck).contains("1");
        assertThat(ratingCheck).contains("5");
    }

    @Test
    @DisplayName("review_images 테이블 존재 확인")
    void reviewImagesTableExists() {
        Number count = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'review_images'"
        ).getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("review_reports 테이블 존재 확인")
    void reviewReportsTableExists() {
        Number count = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'review_reports'"
        ).getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("product_review_summaries 테이블 존재 확인")
    void productReviewSummariesTableExists() {
        Number count = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'product_review_summaries'"
        ).getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("order_item_id UNIQUE 제약 확인")
    void orderItemIdUniqueConstraintExists() {
        String uniqueDef = (String) em.createNativeQuery("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'reviews'::regclass
                  AND contype = 'u'
                  AND conname LIKE '%order_item%'
                """).getSingleResult();

        assertThat(uniqueDef).isNotNull();
        assertThat(uniqueDef).contains("order_item_id");
    }

    @Test
    @DisplayName("인덱스 존재 확인")
    void indexesExist() {
        // idx_reviews_product_created
        Number productIndexCount = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE tablename = 'reviews'
                  AND indexname LIKE '%product%'
                """).getSingleResult();

        // idx_reviews_member_created
        Number memberIndexCount = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE tablename = 'reviews'
                  AND indexname LIKE '%member%'
                """).getSingleResult();

        assertThat(productIndexCount.intValue()).isGreaterThan(0);
        assertThat(memberIndexCount.intValue()).isGreaterThan(0);
    }

    @Test
    @DisplayName("리뷰 데이터 INSERT/SELECT 왕복 가능")
    void insertAndSelectReview() {
        // given - FK 순서 고려: member_grades → members → member_addresses → brands → products → product_options → orders → order_items → reviews
        // 기존 데이터를 사용하거나 새 ID를 사용
        em.createNativeQuery("""
                INSERT INTO member_grades (id, name)
                VALUES (999, 'TEST_GRADE')
                ON CONFLICT DO NOTHING
                """).executeUpdate();

        em.createNativeQuery("""
                INSERT INTO members (id, email, password_hash, name, grade_id)
                VALUES (999, 'review@test.com', 'hash', '테스터', 999)
                """).executeUpdate();

        em.createNativeQuery("""
                INSERT INTO member_addresses (id, member_id, recipient_name, phone,
                                               zipcode, address_main, address_detail, is_default)
                VALUES (999, 999, '테스터', '01000000000', '12345', '서울', '강남구', true)
                """).executeUpdate();

        em.createNativeQuery("""
                INSERT INTO brands (id, name, slug)
                VALUES (999, '테스트브랜드', 'test-brand-999')
                """).executeUpdate();

        em.createNativeQuery("""
                INSERT INTO products (id, name, brand_id, base_price, status)
                VALUES (999, '리뷰테스트상품', 999, 10000, 'ON_SALE')
                """).executeUpdate();

        em.createNativeQuery("""
                INSERT INTO product_options (id, product_id, option_name)
                VALUES (999, 999, '옵션1')
                ON CONFLICT DO NOTHING
                """).executeUpdate();

        em.createNativeQuery("""
                INSERT INTO orders (id, order_no, member_id, status, delivery_address_id,
                                     total_product_amount, final_payment_amount)
                VALUES (999, 'ORD999', 999, 'DELIVERED', 999, 10000, 10000)
                """).executeUpdate();

        em.createNativeQuery("""
                INSERT INTO order_items (id, order_id, product_id, product_option_id,
                                         product_name, option_name, unit_price, quantity, total_amount)
                VALUES (999, 999, 999, 999, '리뷰테스트상품', '옵션1', 10000, 1, 10000)
                """).executeUpdate();

        em.createNativeQuery("""
                INSERT INTO reviews (member_id, product_id, order_item_id, rating, title, body, status)
                VALUES (999, 999, 999, 5, '좋아요', '추천합니다', 'VISIBLE')
                """).executeUpdate();

        // when
        Object[] review = (Object[]) em.createNativeQuery("""
                SELECT id, member_id, product_id, rating, title, body, status
                FROM reviews
                WHERE order_item_id = 999
                """).getSingleResult();

        // then
        assertThat(review).isNotNull();
        assertThat(review[3]).isEqualTo((short) 5); // rating
        assertThat(review[4]).isEqualTo("좋아요"); // title
    }
}
