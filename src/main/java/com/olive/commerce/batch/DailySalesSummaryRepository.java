package com.olive.commerce.batch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 일별 매출 집계 Repository.
 */
@Repository
public interface DailySalesSummaryRepository extends JpaRepository<DailySalesSummary, Long> {

    /**
     * 특정 날짜의 집계를 조회합니다.
     *
     * @param summaryDate 집계 날짜
     * @return 해당 날짜의 모든 집계
     */
    List<DailySalesSummary> findBySummaryDate(LocalDate summaryDate);

    /**
     * 특정 날짜와 상품의 집계를 조회합니다.
     *
     * @param summaryDate 집계 날짜
     * @param productId 상품 ID
     * @return 집계 (없으면 empty)
     */
    Optional<DailySalesSummary> findBySummaryDateAndProductId(LocalDate summaryDate, Long productId);

    /**
     * 특정 날짜와 브랜드의 집계를 조회합니다.
     *
     * @param summaryDate 집계 날짜
     * @param brandId 브랜드 ID
     * @return 집계 목록
     */
    List<DailySalesSummary> findBySummaryDateAndBrandId(LocalDate summaryDate, Long brandId);

    /**
     * 특정 날짜와 카테고리의 집계를 조회합니다.
     *
     * @param summaryDate 집계 날짜
     * @param categoryId 카테고리 ID
     * @return 집계 목록
     */
    List<DailySalesSummary> findBySummaryDateAndCategoryId(LocalDate summaryDate, Long categoryId);

    /**
     * 날짜 범위로 집계를 조회합니다.
     *
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @return 집계 목록
     */
    List<DailySalesSummary> findBySummaryDateBetweenOrderBySummaryDateDesc(
            LocalDate startDate,
            LocalDate endDate
    );

    /**
     * 상품별 일 매출 합계를 계산합니다 (집계용).
     *
     * @param summaryDate 집계 날짜
     * @param productId 상품 ID
     * @return [주문 수, 총 매출액]
     */
    @Query(value = """
        SELECT COALESCE(COUNT(oi.id), 0) AS order_count,
               COALESCE(SUM(oi.unit_price * oi.quantity), 0.00) AS total_amount
        FROM order_items oi
        JOIN orders o ON oi.order_id = o.id
        WHERE o.status = 'PAID'
          AND o.created_at >= :startDate
          AND o.created_at < :endDate
          AND oi.product_id = :productId
        """, nativeQuery = true)
    Object[] computeProductSalesForDate(
            @Param("startDate") java.time.OffsetDateTime startDate,
            @Param("endDate") java.time.OffsetDateTime endDate,
            @Param("productId") Long productId
    );

    /**
     * 브랜드별 일 매출 합계를 계산합니다.
     *
     * @param startDate 시작 시점
     * @param endDate 종료 시점
     * @param brandId 브랜드 ID
     * @return [주문 수, 총 매출액]
     */
    @Query(value = """
        SELECT COALESCE(COUNT(DISTINCT o.id), 0) AS order_count,
               COALESCE(SUM(o.final_payment_amount), 0.00) AS total_amount
        FROM orders o
        JOIN order_items oi ON o.id = oi.order_id
        JOIN product_options po ON oi.product_option_id = po.id
        JOIN products p ON po.product_id = p.id
        WHERE o.status = 'PAID'
          AND o.created_at >= :startDate
          AND o.created_at < :endDate
          AND p.brand_id = :brandId
        """, nativeQuery = true)
    Object[] computeBrandSalesForDate(
            @Param("startDate") java.time.OffsetDateTime startDate,
            @Param("endDate") java.time.OffsetDateTime endDate,
            @Param("brandId") Long brandId
    );

    /**
     * 카테고리별 일 매출 합계를 계산합니다.
     *
     * @param startDate 시작 시점
     * @param endDate 종료 시점
     * @param categoryId 카테고리 ID
     * @return [주문 수, 총 매출액]
     */
    @Query(value = """
        SELECT COALESCE(COUNT(DISTINCT o.id), 0) AS order_count,
               COALESCE(SUM(o.final_payment_amount), 0.00) AS total_amount
        FROM orders o
        JOIN order_items oi ON o.id = oi.order_id
        JOIN product_options po ON oi.product_option_id = po.id
        JOIN products p ON po.product_id = p.id
        WHERE o.status = 'PAID'
          AND o.created_at >= :startDate
          AND o.created_at < :endDate
          AND p.category_id = :categoryId
        """, nativeQuery = true)
    Object[] computeCategorySalesForDate(
            @Param("startDate") java.time.OffsetDateTime startDate,
            @Param("endDate") java.time.OffsetDateTime endDate,
            @Param("categoryId") Long categoryId
    );

    /**
     * 전체 일 매출 합계를 계산합니다.
     *
     * @param startDate 시작 시점
     * @param endDate 종료 시점
     * @return [주문 수, 총 매출액]
     */
    @Query(value = """
        SELECT COALESCE(COUNT(o.id), 0) AS order_count,
               COALESCE(SUM(o.final_payment_amount), 0.00) AS total_amount
        FROM orders o
        WHERE o.status = 'PAID'
          AND o.created_at >= :startDate
          AND o.created_at < :endDate
        """, nativeQuery = true)
    Object[] computeTotalSalesForDate(
            @Param("startDate") java.time.OffsetDateTime startDate,
            @Param("endDate") java.time.OffsetDateTime endDate
    );

    /**
     * 날짜와 카테고리/브랜드/상품 ID (모두 nullable)로 집계를 조회합니다.
     *
     * @param summaryDate 집계 날짜
     * @param categoryId 카테고리 ID (null 가능)
     * @param brandId 브랜드 ID (null 가능)
     * @param productId 상품 ID (null 가능)
     * @return 집계
     */
    @Query("SELECT s FROM DailySalesSummary s WHERE s.summaryDate = :summaryDate " +
            "AND (:categoryId IS NULL OR s.categoryId = :categoryId) " +
            "AND (:brandId IS NULL OR s.brandId = :brandId) " +
            "AND (:productId IS NULL OR s.productId = :productId)")
    Optional<DailySalesSummary> findBySummaryDateAndCategoryIdAndBrandIdAndProductId(
            @Param("summaryDate") LocalDate summaryDate,
            @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId,
            @Param("productId") Long productId
    );
}
