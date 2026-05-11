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
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 상품 옵션 엔티티 (PRD §6.2).
 * 색상, 용량, 세트 구성 등을 표현한다. 재고는 여기에 연결된다 (PRD §20.3).
 */
@Entity
@Table(name = "product_options")
public class ProductOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "option_name", nullable = false, length = 100)
    private String optionName;

    @Column(name = "option_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal optionPrice = BigDecimal.ZERO;

    @Column(name = "status", nullable = false, length = 20)
    private String status = OptionStatus.ON_SALE.name();

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    protected ProductOption() {}

    private ProductOption(String optionName, BigDecimal optionPrice, OptionStatus status) {
        this.optionName = optionName;
        this.optionPrice = optionPrice != null ? optionPrice : BigDecimal.ZERO;
        this.status = status != null ? status.name() : OptionStatus.ON_SALE.name();
    }

    public static ProductOption create(String optionName, BigDecimal optionPrice) {
        if (optionPrice == null || optionPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("optionPrice must be non-negative");
        }
        return new ProductOption(optionName, optionPrice, OptionStatus.ON_SALE);
    }

    public void update(String optionName, BigDecimal optionPrice, OptionStatus status) {
        if (optionPrice != null && optionPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("optionPrice must be non-negative");
        }
        this.optionName = optionName;
        if (optionPrice != null) {
            this.optionPrice = optionPrice;
        }
        if (status != null) {
            this.status = status.name();
        }
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public String getOptionName() { return optionName; }
    public BigDecimal getOptionPrice() { return optionPrice; }
    public OptionStatus getStatus() { return OptionStatus.valueOf(status); }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public enum OptionStatus {
        ON_SALE, SOLD_OUT, STOPPED, HIDDEN;

        @com.fasterxml.jackson.annotation.JsonValue
        public String toValue() {
            return this.name();
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static OptionStatus fromValue(String value) {
            return value != null ? OptionStatus.valueOf(value) : null;
        }
    }
}
