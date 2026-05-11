package com.olive.commerce.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 상품 이미지 Repository.
 */
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    @Query("SELECT pi FROM ProductImage pi WHERE pi.product.id = :productId ORDER BY pi.sortOrder, pi.id")
    List<ProductImage> findByProductIdOrderBySortOrder(@Param("productId") Long productId);
}
