package com.olive.commerce.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 상품 Repository.
 * 페이지네이션과 필터링을 위한 쿼리 메서드를 제공한다.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("""
        SELECT p FROM Product p
        WHERE (:status IS NULL OR p.status = :status)
        AND (:brandId IS NULL OR p.brandId = :brandId)
        AND (:name IS NULL OR p.name LIKE %:name%)
        """)
    Page<Product> findAllWithFilters(
        @Param("status") String status,
        @Param("brandId") Long brandId,
        @Param("name") String name,
        Pageable pageable
    );

    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.brand LEFT JOIN FETCH p.images WHERE p.id = :id")
    Optional<Product> findByIdWithDetails(@Param("id") Long id);

    /**
     * 전체 상품 ID 목록 조회 (랭킹 재계산용).
     */
    @Query("SELECT p.id FROM Product p")
    List<Long> findAllIds();

    /**
     * 상품의 sales_count를 주문 아이템 집계로 일괄 갱신합니다 (PAID/DELIVERED 기준).
     * 주문이 없는 상품은 0으로 유지됩니다.
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE products p
        SET sales_count = COALESCE((
            SELECT SUM(oi.quantity)
            FROM order_items oi
            JOIN orders o ON oi.order_id = o.id
            WHERE oi.product_id = p.id
              AND o.status IN ('PAID', 'DELIVERED')
        ), 0)
        """, nativeQuery = true)
    int refreshAllSalesCounts();
}
