package com.olive.commerce.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
