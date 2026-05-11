package com.olive.commerce.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 주문 가격 요약 엔티티 (PRD §7.6, audit trail).
 * <p>
 * 주문 생성 시점의 가격 계산 상세를 저장합니다.
 * 환불/취소 시 원래 결제 금액을 복원하기 위한 감사 추적용입니다.
 */
@Entity
@Table(name = "order_price_summaries")
public class OrderPriceSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "coupon_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal couponDiscount = BigDecimal.ZERO;

    @Column(name = "point_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal pointDiscount = BigDecimal.ZERO;

    @Column(name = "shipping_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal shippingFee = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal grandTotal;

    protected OrderPriceSummary() {}

    private OrderPriceSummary(Order order) {
        this.order = order;
    }

    public static OrderPriceSummary create(Order order) {
        return new OrderPriceSummary(order);
    }

    public void setPriceDetails(
            BigDecimal subtotal,
            BigDecimal couponDiscount,
            BigDecimal pointDiscount,
            BigDecimal shippingFee,
            BigDecimal grandTotal
    ) {
        this.subtotal = subtotal;
        this.couponDiscount = couponDiscount;
        this.pointDiscount = pointDiscount;
        this.shippingFee = shippingFee;
        this.grandTotal = grandTotal;
    }

    // Getters
    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getCouponDiscount() { return couponDiscount; }
    public BigDecimal getPointDiscount() { return pointDiscount; }
    public BigDecimal getShippingFee() { return shippingFee; }
    public BigDecimal getGrandTotal() { return grandTotal; }
}
