package com.olive.commerce.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 상품 옵션 Repository.
 */
public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    @Query("SELECT po FROM ProductOption po WHERE po.product.id = :productId ORDER BY po.id")
    List<ProductOption> findByProductIdOrderByProductId(@Param("productId") Long productId);

    /**
     * ID로 옵션 조회 (상품 정보 포함).
     * <p>
     * 주문 생성 시 상품 판매 상태 검증을 위해 fetch join 사용.
     */
    @Query("SELECT po FROM ProductOption po LEFT JOIN FETCH po.product WHERE po.id = :id")
    Optional<ProductOption> findByIdWithProduct(@Param("id") Long id);
}
