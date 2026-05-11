package com.olive.commerce.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 상품 엔티티 (PRD §6.2, §7.2).
 * 브랜드, 옵션, 이미지, 카테고리 매핑을 포함한다.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @Column(name = "brand_id", insertable = false, updatable = false)
    private Long brandId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "status", nullable = false, length = 20)
    private String status = ProductStatus.DRAFT.name();

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "sale_price", precision = 12, scale = 2)
    private BigDecimal salePrice;

    @OneToMany(mappedBy = "product", cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 10)
    private List<ProductOption> options = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 10)
    private List<ProductImage> images = new ArrayList<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    protected Product() {}

    private Product(Long brandId, String name, String description, BigDecimal basePrice, BigDecimal salePrice, ProductStatus status) {
        this.brandId = brandId;
        this.name = name;
        this.description = description;
        this.basePrice = basePrice;
        this.salePrice = salePrice;
        this.status = status != null ? status.name() : ProductStatus.DRAFT.name();
    }

    public static Product create(Long brandId, String name, String description, BigDecimal basePrice, BigDecimal salePrice) {
        if (basePrice == null || basePrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("basePrice must be non-negative");
        }
        if (salePrice != null && salePrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("salePrice must be non-negative");
        }
        return new Product(brandId, name, description, basePrice, salePrice, ProductStatus.DRAFT);
    }

    public static Product createWithStatus(Long brandId, String name, String description, BigDecimal basePrice, BigDecimal salePrice, ProductStatus status) {
        if (basePrice == null || basePrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("basePrice must be non-negative");
        }
        if (salePrice != null && salePrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("salePrice must be non-negative");
        }
        return new Product(brandId, name, description, basePrice, salePrice, status != null ? status : ProductStatus.DRAFT);
    }

    public void update(String name, String description, BigDecimal basePrice, BigDecimal salePrice, ProductStatus status) {
        if (basePrice != null && basePrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("basePrice must be non-negative");
        }
        if (salePrice != null && salePrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("salePrice must be non-negative");
        }

        ProductStatus currentStatus = ProductStatus.valueOf(this.status);
        if (status != null && !status.equals(currentStatus) && !currentStatus.isValidTransition(status)) {
            throw new IllegalArgumentException(
                String.format("Invalid status transition: %s → %s", currentStatus, status)
            );
        }

        this.name = name;
        this.description = description;
        if (basePrice != null) {
            this.basePrice = basePrice;
        }
        this.salePrice = salePrice;
        if (status != null) {
            this.status = status.name();
        }
    }

    public void addOption(ProductOption option) {
        this.options.add(option);
        option.setProduct(this);
    }

    public void addImage(ProductImage image) {
        this.images.add(image);
        image.setProduct(this);
    }

    // Brand 관계 설정용 (Service에서 사용)
    void setBrand(Brand brand) {
        this.brand = brand;
        this.brandId = brand != null ? brand.getId() : null;
    }

    public ProductStatus getStatus() {
        return ProductStatus.valueOf(status);
    }

    public Long getId() { return id; }
    public Long getBrandId() { return brandId; }
    public Brand getBrand() { return brand; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getBasePrice() { return basePrice; }
    public BigDecimal getSalePrice() { return salePrice; }
    public List<ProductOption> getOptions() { return options; }
    public List<ProductImage> getImages() { return images; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public enum ProductStatus {
        DRAFT, ON_SALE, SOLD_OUT, STOPPED, HIDDEN;

        // JSON 직렬화 시 enum 이름("DRAFT")으로 반환
        @com.fasterxml.jackson.annotation.JsonValue
        public String toValue() {
            return this.name();
        }

        // JSON 역직렬화 시 문자열을 enum으로 변환
        @com.fasterxml.jackson.annotation.JsonCreator
        public static ProductStatus fromValue(String value) {
            return value != null ? ProductStatus.valueOf(value) : null;
        }

        public boolean isValidTransition(ProductStatus to) {
            if (to == HIDDEN) {
                return true; // HIDDEN is always allowed
            }
            return switch (this) {
                case DRAFT -> to == ON_SALE;
                case ON_SALE -> to == SOLD_OUT || to == STOPPED;
                case SOLD_OUT -> to == ON_SALE; // Can come back to ON_SALE
                case STOPPED -> false; // Cannot transition from STOPPED
                case HIDDEN -> to == DRAFT || to == ON_SALE; // Can unhide
            };
        }
    }
}
