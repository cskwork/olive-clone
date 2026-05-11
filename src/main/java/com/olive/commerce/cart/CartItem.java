package com.olive.commerce.cart;

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

import java.time.OffsetDateTime;

/**
 * 장바구니 아이템 엔티티 (PRD §6.4).
 *
 * <p>UNIQUE (cart_id, product_option_id)로 중복 추가 방지.
 */
@Entity
@Table(name = "cart_items")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity = 1;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    protected CartItem() {}

    private CartItem(Cart cart, Long productOptionId, Integer quantity) {
        this.cart = cart;
        this.productOptionId = productOptionId;
        this.quantity = quantity != null && quantity > 0 ? quantity : 1;
    }

    /**
     * 새 장바구니 아이템 생성.
     */
    public static CartItem create(Cart cart, Long productOptionId, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        return new CartItem(cart, productOptionId, quantity);
    }

    /**
     * 수량 변경.
     */
    public void updateQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        this.quantity = quantity;
    }

    /**
     * 수량 증분.
     */
    public void increment(Integer delta) {
        if (delta == null || delta <= 0) {
            throw new IllegalArgumentException("Delta must be positive: " + delta);
        }
        this.quantity += delta;
    }

    public Long getId() {
        return id;
    }

    public Cart getCart() {
        return cart;
    }

    public Long getProductOptionId() {
        return productOptionId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
