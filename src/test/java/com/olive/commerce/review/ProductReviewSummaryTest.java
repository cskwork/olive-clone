package com.olive.commerce.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProductReviewSummary 집계 로직 단위 테스트.
 */
class ProductReviewSummaryTest {

    @Test
    @DisplayName("첫 리뷰 추가 시 평점과 개수가 정확히 계산됨")
    void addReview_firstReview_calculatesCorrectly() {
        ProductReviewSummary summary = ProductReviewSummary.create(1L);

        summary.addReview((short) 5);

        assertThat(summary.getAvgRating()).isEqualTo(new BigDecimal("5.00"));
        assertThat(summary.getReviewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("여러 리뷰 추가 시 평균 평점이 올바르게 계산됨")
    void addReview_multipleReviews_calculatesAverage() {
        ProductReviewSummary summary = ProductReviewSummary.create(1L);

        summary.addReview((short) 5);
        summary.addReview((short) 4);
        summary.addReview((short) 3);

        // (5 + 4 + 3) / 3 = 4.00
        assertThat(summary.getAvgRating()).isEqualTo(new BigDecimal("4.00"));
        assertThat(summary.getReviewCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("소수점 반올림 검증")
    void addReview_roundingCorrect() {
        ProductReviewSummary summary = ProductReviewSummary.create(1L);

        summary.addReview((short) 5);
        summary.addReview((short) 4);

        // (5 + 4) / 2 = 4.50
        assertThat(summary.getAvgRating()).isEqualTo(new BigDecimal("4.50"));
        assertThat(summary.getReviewCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("복잡한 평균 계산 검증")
    void addReview_complexAverage() {
        ProductReviewSummary summary = ProductReviewSummary.create(1L);

        summary.addReview((short) 5);
        summary.addReview((short) 5);
        summary.addReview((short) 4);
        summary.addReview((short) 3);
        summary.addReview((short) 2);

        // (5 + 5 + 4 + 3 + 2) / 5 = 19 / 5 = 3.80
        assertThat(summary.getAvgRating()).isEqualTo(new BigDecimal("3.80"));
        assertThat(summary.getReviewCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("리뷰 제거 시 평균 재계산")
    void removeReview_recalculateAverage() {
        ProductReviewSummary summary = ProductReviewSummary.create(1L);

        summary.addReview((short) 5);
        summary.addReview((short) 4);
        summary.addReview((short) 3);

        // 현재: 평균 4.00, 개수 3
        assertThat(summary.getAvgRating()).isEqualTo(new BigDecimal("4.00"));

        // 3점 리뷰 제거: (5 + 4) / 2 = 4.50
        summary.removeReview((short) 3);

        assertThat(summary.getAvgRating()).isEqualTo(new BigDecimal("4.50"));
        assertThat(summary.getReviewCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("마지막 리뷰 제거 시 초기화")
    void removeReview_lastReview_resetsToZero() {
        ProductReviewSummary summary = ProductReviewSummary.create(1L);

        summary.addReview((short) 5);
        assertThat(summary.getReviewCount()).isEqualTo(1);

        summary.removeReview((short) 5);

        assertThat(summary.getAvgRating()).isEqualTo(BigDecimal.ZERO);
        assertThat(summary.getReviewCount()).isEqualTo(0);
    }
}
