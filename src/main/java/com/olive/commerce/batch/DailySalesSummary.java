package com.olive.commerce.batch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일별 매출 집계 엔티티 (PRD §17).
 * <p>카테고리/브랜드/상품별 일 매출을 집계합니다.
 */
@Entity
@Table(name = "daily_sales_summaries",
       uniqueConstraints = {
           @UniqueConstraint(name = "uniq_daily_sales_summary", columnNames = {"summary_date", "category_id", "brand_id", "product_id"})
       }
)
public class DailySalesSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "summary_date", nullable = false)
    private LocalDate summaryDate;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "brand_id")
    private Long brandId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "order_count", nullable = false)
    private Integer orderCount;

    @Column(name = "total_sales_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalSalesAmount;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private java.time.OffsetDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private java.time.OffsetDateTime updatedAt;

    protected DailySalesSummary() {}

    private DailySalesSummary(
            LocalDate summaryDate,
            Long categoryId,
            Long brandId,
            Long productId
    ) {
        this.summaryDate = summaryDate;
        this.categoryId = categoryId;
        this.brandId = brandId;
        this.productId = productId;
        this.orderCount = 0;
        this.totalSalesAmount = BigDecimal.ZERO;
    }

    /**
     * 새로운 일별 매출 요약을 생성합니다.
     */
    public static DailySalesSummary create(
            LocalDate summaryDate,
            Long categoryId,
            Long brandId,
            Long productId
    ) {
        return new DailySalesSummary(summaryDate, categoryId, brandId, productId);
    }

    /**
     * 주문 수와 매출 금액을 더합니다 (UPSERT용).
     */
    public void addAmounts(int additionalOrders, BigDecimal additionalAmount) {
        this.orderCount += additionalOrders;
        this.totalSalesAmount = this.totalSalesAmount.add(additionalAmount);
    }

    /**
     * 집계 재실행 시 계산된 결과로 기존 값을 대체합니다.
     */
    public void replaceAmounts(int orderCount, BigDecimal totalSalesAmount) {
        this.orderCount = orderCount;
        this.totalSalesAmount = totalSalesAmount;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public LocalDate getSummaryDate() { return summaryDate; }
    public Long getCategoryId() { return categoryId; }
    public Long getBrandId() { return brandId; }
    public Long getProductId() { return productId; }
    public Integer getOrderCount() { return orderCount; }
    public BigDecimal getTotalSalesAmount() { return totalSalesAmount; }

    public void setOrderCount(Integer orderCount) { this.orderCount = orderCount; }
    public void setTotalSalesAmount(BigDecimal totalSalesAmount) { this.totalSalesAmount = totalSalesAmount; }
}
