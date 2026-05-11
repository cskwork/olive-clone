package com.olive.commerce.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 주문 상품 엔티티 (PRD §20.2 snapshot-at-create).
 * <p>
 * 상품/옵션의 이름, 가격을 주문 시점에 스냅샷으로 저장합니다.
 * 원본 상품이 수정되더라도 주문 이력은 변경되지 않습니다.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;       // Snapshot

    @Column(name = "option_name", nullable = false, length = 255)
    private String optionName;        // Snapshot

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;     // Snapshot

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected OrderItem() {}

    private OrderItem(
            Order order,
            Long productId,
            Long productOptionId,
            String productName,
            String optionName,
            BigDecimal unitPrice,
            int quantity
    ) {
        this.order = order;
        this.productId = productId;
        this.productOptionId = productOptionId;
        this.productName = productName;
        this.optionName = optionName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public static OrderItem create(
            Order order,
            Long productId,
            Long productOptionId,
            String productName,
            String optionName,
            BigDecimal unitPrice,
            int quantity
    ) {
        return new OrderItem(order, productId, productOptionId, productName, optionName, unitPrice, quantity);
    }

    // Getters
    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public Long getProductId() { return productId; }
    public Long getProductOptionId() { return productOptionId; }
    public String getProductName() { return productName; }
    public String getOptionName() { return optionName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
