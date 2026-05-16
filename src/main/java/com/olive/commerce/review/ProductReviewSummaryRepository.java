package com.olive.commerce.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 상품 리뷰 요약 리포지토리.
 */
public interface ProductReviewSummaryRepository extends JpaRepository<ProductReviewSummary, Long> {

    /**
     * 상품별 리뷰 요약 조회.
     */
    Optional<ProductReviewSummary> findById(Long productId);

    /**
     * 상품 ID로 리뷰 요약 조회 (없으면 빈 Optional).
     */
    default Optional<ProductReviewSummary> findByProductId(Long productId) {
        return findById(productId);
    }
}
