package com.olive.commerce.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
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

    /**
     * 여러 상품의 리뷰 요약을 한 번에 조회 (N+1 방지용 배치 조회).
     *
     * @param productIds 상품 ID 목록
     * @return 해당 상품들의 리뷰 요약 (존재하는 것만 반환)
     */
    List<ProductReviewSummary> findByProductIdIn(Collection<Long> productIds);
}
