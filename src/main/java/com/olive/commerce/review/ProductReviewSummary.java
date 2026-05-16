package com.olive.commerce.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 상품 리뷰 요약 엔티티 (PRD §6.10).
 * <p>
 * 집계 테이블로 핫 읽기 경로(product detail)에서의 실시간 계산을 방지합니다.
 * ReviewCreatedEvent 수신 시 배치/단건으로 갱신됩니다.
 */
@Entity
@Table(name = "product_review_summaries")
public class ProductReviewSummary {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "avg_rating", nullable = false, precision = 3, scale = 2)
    private BigDecimal avgRating = BigDecimal.ZERO;

    @Column(name = "review_count", nullable = false)
    private int reviewCount = 0;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    protected ProductReviewSummary() {}

    private ProductReviewSummary(Long productId) {
        this.productId = productId;
    }

    public static ProductReviewSummary create(Long productId) {
        return new ProductReviewSummary(productId);
    }

    /**
     * 새 리뷰 추가 시 평균 평점과 리뷰 수를 갱신합니다.
     *
     * @param newRating 새 리뷰의 평점 (1-5)
     */
    public void addReview(short newRating) {
        BigDecimal newRatingDecimal = BigDecimal.valueOf(newRating);
        BigDecimal currentSum = avgRating.multiply(BigDecimal.valueOf(reviewCount));
        BigDecimal newSum = currentSum.add(newRatingDecimal);
        int newCount = reviewCount + 1;

        this.avgRating = newSum.divide(BigDecimal.valueOf(newCount), 2, java.math.RoundingMode.HALF_UP);
        this.reviewCount = newCount;
    }

    /**
     * 리뷰 삭제(숨김) 시 평균 평점과 리뷰 수를 재계산합니다.
     *
     * @param removedRating 제거된 리뷰의 평점
     */
    public void removeReview(short removedRating) {
        if (reviewCount <= 1) {
            this.avgRating = BigDecimal.ZERO;
            this.reviewCount = 0;
            return;
        }

        BigDecimal removedRatingDecimal = BigDecimal.valueOf(removedRating);
        BigDecimal currentSum = avgRating.multiply(BigDecimal.valueOf(reviewCount));
        BigDecimal newSum = currentSum.subtract(removedRatingDecimal);
        int newCount = reviewCount - 1;

        this.avgRating = newSum.divide(BigDecimal.valueOf(newCount), 2, java.math.RoundingMode.HALF_UP);
        this.reviewCount = newCount;
    }

    // Getters
    public Long getProductId() { return productId; }
    public BigDecimal getAvgRating() { return avgRating; }
    public int getReviewCount() { return reviewCount; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
