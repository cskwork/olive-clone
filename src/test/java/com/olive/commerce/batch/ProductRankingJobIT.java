package com.olive.commerce.batch;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProductRankingJob 통합 테스트.
 *
 * AC: recomputeAllRankings()가 product_rankings 테이블을 올바르게 채우는지 검증.
 * 멱등성(idempotency): 재실행 시 동일 결과.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ProductRankingJobIT extends PostgresIntegrationSupport {

    @Autowired
    private ProductRankingJob productRankingJob;

    @Autowired
    private ProductRankingRepository productRankingRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void setUp() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM product_review_summaries WHERE product_id > 0").executeUpdate();
            em.createNativeQuery("DELETE FROM product_rankings WHERE product_id > 0").executeUpdate();
            // Reset seed product sales_count
            em.createNativeQuery("UPDATE products SET sales_count = 0 WHERE id = 1").executeUpdate();
        });
    }

    @Test
    @DisplayName("recomputeAllRankings populates product_rankings for each product")
    void recomputeAllRankings_populatesProductRankings() {
        // Given: product 1 exists (Flyway seed); no review summary → zeros
        // When
        int count = new TransactionTemplate(txManager).execute(s ->
            productRankingJob.recomputeAllRankings()
        );

        // Then: one ranking row per product
        assertThat(count).isGreaterThanOrEqualTo(1);

        Optional<ProductRanking> ranking = productRankingRepository.findByProductId(1L);
        assertThat(ranking).isPresent();
        assertThat(ranking.get().getRankScore()).isNotNull();
        // sales=0, review=0, rating=0 → score=0
        assertThat(ranking.get().getRankScore().compareTo(java.math.BigDecimal.ZERO)).isEqualTo(0);
    }

    @Test
    @DisplayName("recomputeAllRankings computes correct score with review data")
    void recomputeAllRankings_computesCorrectScore_withReviewData() {
        // Given: set sales_count=10 and insert review summary
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("UPDATE products SET sales_count = 10 WHERE id = 1").executeUpdate();
            em.createNativeQuery("""
                INSERT INTO product_review_summaries (product_id, avg_rating, review_count)
                VALUES (1, 4.00, 20)
                ON CONFLICT (product_id) DO UPDATE
                    SET avg_rating = EXCLUDED.avg_rating,
                        review_count = EXCLUDED.review_count
                """).executeUpdate();
        });

        // When
        new TransactionTemplate(txManager).executeWithoutResult(s ->
            productRankingJob.recomputeAllRankings()
        );

        // Then: score = 10*0.5 + 20*0.3 + 4.0*0.2 = 5.0 + 6.0 + 0.8 = 11.8
        Optional<ProductRanking> ranking = productRankingRepository.findByProductId(1L);
        assertThat(ranking).isPresent();

        java.math.BigDecimal expectedScore = new java.math.BigDecimal("11.8000");
        assertThat(ranking.get().getRankScore().compareTo(expectedScore)).isEqualTo(0);
        assertThat(ranking.get().getSalesCount()).isEqualTo(10);
        assertThat(ranking.get().getReviewCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("recomputeAllRankings is idempotent: second run produces same result")
    void recomputeAllRankings_isIdempotent() {
        // Given
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("UPDATE products SET sales_count = 5 WHERE id = 1").executeUpdate();
        });

        // Run twice
        new TransactionTemplate(txManager).executeWithoutResult(s ->
            productRankingJob.recomputeAllRankings()
        );
        Optional<ProductRanking> afterFirst = productRankingRepository.findByProductId(1L);

        new TransactionTemplate(txManager).executeWithoutResult(s ->
            productRankingJob.recomputeAllRankings()
        );
        Optional<ProductRanking> afterSecond = productRankingRepository.findByProductId(1L);

        // Then: same score both times
        assertThat(afterFirst).isPresent();
        assertThat(afterSecond).isPresent();
        assertThat(afterFirst.get().getRankScore().compareTo(afterSecond.get().getRankScore())).isEqualTo(0);
    }

    @Test
    @DisplayName("product with no ranking data gets score=0")
    void recomputeAllRankings_zeroDataProduct_getsZeroScore() {
        // Given: no sales, no reviews for product 1

        // When
        new TransactionTemplate(txManager).executeWithoutResult(s ->
            productRankingJob.recomputeAllRankings()
        );

        // Then
        Optional<ProductRanking> ranking = productRankingRepository.findByProductId(1L);
        assertThat(ranking).isPresent();
        assertThat(ranking.get().getSalesCount()).isEqualTo(0);
        assertThat(ranking.get().getReviewCount()).isEqualTo(0);
        assertThat(ranking.get().getRankScore().compareTo(java.math.BigDecimal.ZERO)).isEqualTo(0);
    }
}
