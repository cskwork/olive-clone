package com.olive.commerce.wishlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 찜 목록 아이템 엔티티 (OLV-W01).
 *
 * <p>UNIQUE (member_id, product_id)로 중복 추가 방지 (idempotent add).
 */
@Entity
@Table(name = "wishlist_items")
public class WishlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected WishlistItem() {}

    private WishlistItem(Long memberId, Long productId) {
        this.memberId = memberId;
        this.productId = productId;
    }

    /**
     * 새 찜 아이템 생성.
     */
    public static WishlistItem create(Long memberId, Long productId) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId must not be null");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId must not be null");
        }
        return new WishlistItem(memberId, productId);
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getProductId() {
        return productId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
