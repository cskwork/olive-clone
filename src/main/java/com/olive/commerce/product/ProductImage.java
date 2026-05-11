package com.olive.commerce.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 상품 이미지 엔티티 (PRD §6.2).
 * 실제 이미지는 S3-compatible 저장소에 저장되며, 여기에는 URL만 저장된다.
 */
@Entity
@Table(name = "product_images")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "url", nullable = false, length = 512)
    private String url;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "is_thumbnail", nullable = false)
    private Boolean isThumbnail = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected ProductImage() {}

    private ProductImage(String url, Integer sortOrder, Boolean isThumbnail) {
        this.url = url;
        this.sortOrder = sortOrder != null ? sortOrder : 0;
        this.isThumbnail = isThumbnail != null ? isThumbnail : false;
    }

    public static ProductImage create(String url, Integer sortOrder, Boolean isThumbnail) {
        return new ProductImage(url, sortOrder, isThumbnail);
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public String getUrl() { return url; }
    public Integer getSortOrder() { return sortOrder; }
    public Boolean getIsThumbnail() { return isThumbnail; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
