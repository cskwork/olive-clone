package com.olive.commerce.product;

import java.io.Serializable;
import java.util.Objects;

/**
 * ProductCategoryMapping의 복합 키 클래스.
 */
public class ProductCategoryMappingPK implements Serializable {

    private Long productId;
    private Long categoryId;

    protected ProductCategoryMappingPK() {}

    public ProductCategoryMappingPK(Long productId, Long categoryId) {
        this.productId = productId;
        this.categoryId = categoryId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProductCategoryMappingPK that = (ProductCategoryMappingPK) o;
        return Objects.equals(productId, that.productId) && Objects.equals(categoryId, that.categoryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productId, categoryId);
    }

    public Long getProductId() { return productId; }
    public Long getCategoryId() { return categoryId; }
}
