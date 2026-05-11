package com.olive.commerce.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * 상품-카테고리 M:N 매핑 엔티티.
 * 복합 키 (product_id, category_id)를 사용한다.
 */
@Entity
@Table(name = "product_category_mapping")
@IdClass(ProductCategoryMappingPK.class)
public class ProductCategoryMapping {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Id
    @Column(name = "category_id")
    private Long categoryId;

    @ManyToOne
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private Product product;

    @ManyToOne
    @JoinColumn(name = "category_id", insertable = false, updatable = false)
    private Category category;

    protected ProductCategoryMapping() {}

    private ProductCategoryMapping(Long productId, Long categoryId) {
        this.productId = productId;
        this.categoryId = categoryId;
    }

    public static ProductCategoryMapping create(Long productId, Long categoryId) {
        return new ProductCategoryMapping(productId, categoryId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProductCategoryMapping that = (ProductCategoryMapping) o;
        return Objects.equals(productId, that.productId) && Objects.equals(categoryId, that.categoryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productId, categoryId);
    }

    public Long getProductId() { return productId; }
    public Long getCategoryId() { return categoryId; }
    public Category getCategory() { return category; }
}
