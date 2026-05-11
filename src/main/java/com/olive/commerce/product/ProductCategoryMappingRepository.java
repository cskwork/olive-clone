package com.olive.commerce.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 상품-카테고리 매핑 Repository.
 */
public interface ProductCategoryMappingRepository extends JpaRepository<ProductCategoryMapping, ProductCategoryMappingPK> {

    @Query("SELECT pcm FROM ProductCategoryMapping pcm JOIN FETCH pcm.category WHERE pcm.productId = :productId")
    List<ProductCategoryMapping> findByProductIdWithCategory(@Param("productId") Long productId);

    void deleteByProductId(Long productId);
}
