package com.olive.commerce.batch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 상품 랭킹 Repository.
 */
@Repository
public interface ProductRankingRepository extends JpaRepository<ProductRanking, Long> {

    /**
     * 상품으로 랭킹을 조회합니다.
     *
     * @param productId 상품 ID
     * @return 랭킹 (없으면 empty)
     */
    Optional<ProductRanking> findByProductId(Long productId);

    /**
     * 랭킹 점수 기준 Top-N을 조회합니다.
     *
     * @param limit 조회할 건수
     * @return 상위 N개 랭킹
     */
    @Query("SELECT pr FROM ProductRanking pr ORDER BY pr.rankScore DESC, pr.productId ASC")
    List<ProductRanking> findTopByRankScore(int limit);

    /**
     * 판매량 기준 Top-N을 조회합니다.
     *
     * @param limit 조회할 건수
     * @return 상위 N개 랭킹
     */
    @Query("SELECT pr FROM ProductRanking pr ORDER BY pr.salesCount DESC, pr.productId ASC")
    List<ProductRanking> findTopBySalesCount(int limit);

    /**
     * 모든 랭킹을 랭킹 점수 순으로 조회합니다.
     *
     * @return 전체 랭킹 (점수 내림차순)
     */
    @Query("SELECT pr FROM ProductRanking pr ORDER BY pr.rankScore DESC, pr.productId ASC")
    List<ProductRanking> findAllOrderByRankScoreDesc();

    /**
     * 상품의 판매 수량을 집계합니다 (PAID 주문 기준).
     *
     * @param productId 상품 ID
     * @return 총 판매 수량
     */
    @Query(value = """
        SELECT COALESCE(SUM(oi.quantity), 0)
        FROM order_items oi
        JOIN orders o ON oi.order_id = o.id
        WHERE o.status = 'PAID'
          AND oi.product_id = :productId
        """, nativeQuery = true)
    Integer countSalesByProduct(@Param("productId") Long productId);

    /**
     * 상품의 리뷰 수와 평균 평점을 조회합니다.
     *
     * @param productId 상품 ID
     * @return [리뷰 수, 평균 평점]
     */
    @Query(value = """
        SELECT COALESCE(COUNT(r.id), 0) AS review_count,
               COALESCE(AVG(r.rating), 0.0) AS avg_rating
        FROM reviews r
        WHERE r.product_id = :productId
          AND r.status = 'VISIBLE'
        """, nativeQuery = true)
    Object[] getReviewStatsByProduct(@Param("productId") Long productId);
}
