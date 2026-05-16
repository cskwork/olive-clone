package com.olive.commerce.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 리뷰 이미지 리포지토리.
 */
public interface ReviewImageRepository extends JpaRepository<ReviewImage, Long> {

    /**
     * 리뷰별 이미지 목록 조회 (정렬순).
     */
    List<ReviewImage> findByReviewIdOrderBySortOrderAsc(Long reviewId);
}
