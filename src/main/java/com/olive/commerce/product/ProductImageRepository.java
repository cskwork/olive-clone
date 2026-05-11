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

    /**
     * product별 썸네일(가장 작은 sort_order의 url) 한 건씩을 반환.
     * SearchService의 hit hydration에서 N+1을 회피하기 위해 사용.
     * 반환 row 모양: {@code [product_id (Long), url (String)]}.
     */
    @Query(value = """
        SELECT DISTINCT ON (product_id) product_id, url
        FROM product_images
        WHERE product_id IN (:ids)
        ORDER BY product_id, sort_order, id
        """, nativeQuery = true)
    List<Object[]> findThumbnailUrlsByProductIds(@Param("ids") List<Long> ids);
}
