package com.olive.commerce.batch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 상품 랭킹 엔티티 (PRD §17).
 * <p>가중 평균 점수로 상품 랭킹을 관리합니다.
 * <p>랭킹 점수 = sales_count * 0.5 + review_count * 0.3 + avg_rating * 0.2
 */
@Entity
@Table(name = "product_rankings")
public class ProductRanking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, unique = true)
    private Long productId;

    @Column(name = "rank_score", nullable = false, precision = 10, scale = 4)
    private BigDecimal rankScore;

    @Column(name = "sales_count", nullable = false)
    private Integer salesCount;

    @Column(name = "review_count", nullable = false)
    private Integer reviewCount;

    @Column(name = "avg_rating", nullable = false, precision = 3, scale = 2)
    private BigDecimal avgRating;

    @Column(name = "computed_at", nullable = false)
    private OffsetDateTime computedAt;

    protected ProductRanking() {}

    private ProductRanking(Long productId) {
        this.productId = productId;
        this.rankScore = BigDecimal.ZERO;
        this.salesCount = 0;
        this.reviewCount = 0;
        this.avgRating = BigDecimal.ZERO;
        this.computedAt = OffsetDateTime.now();
    }

    /**
     * 새로운 상품 랭킹을 생성합니다.
     */
    public static ProductRanking create(Long productId) {
        return new ProductRanking(productId);
    }

    /**
     * 랭킹 데이터를 업데이트합니다.
     */
    public void update(int salesCount, int reviewCount, BigDecimal avgRating) {
        this.salesCount = salesCount;
        this.reviewCount = reviewCount;
        this.avgRating = avgRating != null ? avgRating : BigDecimal.ZERO;
        this.rankScore = computeScore(salesCount, reviewCount, this.avgRating);
        this.computedAt = OffsetDateTime.now();
    }

    /**
     * 가중 평균 점수를 계산합니다.
     * <p>score = sales_count * 0.5 + review_count * 0.3 + avg_rating * 0.2
     *
     * @param salesCount  판매 수량
     * @param reviewCount 리뷰 수
     * @param avgRating   평균 평점 (0-5)
     * @return 랭킹 점수
     */
    private BigDecimal computeScore(int salesCount, int reviewCount, BigDecimal avgRating) {
        // 판매 수량 가중치: 0.5
        BigDecimal salesWeight = BigDecimal.valueOf(0.5);
        BigDecimal salesScore = BigDecimal.valueOf(salesCount).multiply(salesWeight);

        // 리뷰 수 가중치: 0.3
        BigDecimal reviewWeight = BigDecimal.valueOf(0.3);
        BigDecimal reviewScore = BigDecimal.valueOf(reviewCount).multiply(reviewWeight);

        // 평균 평점 가중치: 0.2
        BigDecimal ratingWeight = BigDecimal.valueOf(0.2);
        BigDecimal ratingScore = avgRating.multiply(ratingWeight);

        return salesScore.add(reviewScore).add(ratingScore);
    }

    // Getters
    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public BigDecimal getRankScore() { return rankScore; }
    public Integer getSalesCount() { return salesCount; }
    public Integer getReviewCount() { return reviewCount; }
    public BigDecimal getAvgRating() { return avgRating; }
    public OffsetDateTime getComputedAt() { return computedAt; }
}
