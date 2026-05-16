package com.olive.commerce.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 리뷰 리포지토리.
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    /**
     * 회원별 리뷰 목록 조회 (최신순).
     */
    Page<Review> findByMemberIdOrderByCreatedAtDesc(Long memberId, Pageable pageable);

    /**
     * 상품별 공개 리뷰 목록 조회 (최신순).
     */
    @Query("SELECT r FROM Review r WHERE r.productId = :productId AND r.status = 'VISIBLE' ORDER BY r.createdAt DESC")
    Page<Review> findVisibleByProductId(@Param("productId") Long productId, Pageable pageable);

    /**
     * order_item_id로 리뷰 조회.
     */
    Optional<Review> findByOrderItemId(Long orderItemId);

    /**
     * 회원이 특정 상품에 대해 작성한 리뷰 수 조회.
     */
    @Query("SELECT COUNT(r) FROM Review r WHERE r.memberId = :memberId AND r.productId = :productId")
    long countByMemberIdAndProductId(@Param("memberId") Long memberId, @Param("productId") Long productId);

    /**
     * 리뷰 자격 검증: 주문 상품이 회원 소유이며 배송 완료 상태인지 확인.
     */
    @Query("""
        SELECT CASE WHEN COUNT(o) > 0 THEN true ELSE false END
        FROM Order o
        JOIN o.items i
        WHERE i.id = :orderItemId
          AND o.memberId = :memberId
          AND o.status = 'DELIVERED'
        """)
    boolean isEligibleForReview(@Param("memberId") Long memberId, @Param("orderItemId") Long orderItemId);

    /**
     * 회원이 특정 주문 상품에 대해 이미 리뷰를 작성했는지 확인.
     */
    boolean existsByMemberIdAndOrderItemId(Long memberId, Long orderItemId);
}
