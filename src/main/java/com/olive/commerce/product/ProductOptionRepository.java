package com.olive.commerce.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 상품 옵션 Repository.
 */
public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    @Query("SELECT po FROM ProductOption po WHERE po.product.id = :productId ORDER BY po.id")
    List<ProductOption> findByProductIdOrderByProductId(@Param("productId") Long productId);
}
