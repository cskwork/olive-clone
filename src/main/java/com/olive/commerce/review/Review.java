package com.olive.commerce.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 리뷰 엔티티 (PRD §6.10).
 * <p>
 * 배송 완료(DELIVERED)된 주문 상품에 대해 작성된 리뷰를 나타냅니다.
 * order_item_id UNIQUE 제약으로 하나의 주문 상품당 하나의 리뷰만 작성 가능합니다.
 */
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "order_item_id", nullable = false, unique = true)
    private Long orderItemId;

    @Column(name = "rating", nullable = false)
    private Short rating;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "status", nullable = false, length = 20)
    private String status = ReviewStatus.VISIBLE.name();

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "review")
    private List<ReviewImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "review")
    private List<ReviewReport> reports = new ArrayList<>();

    protected Review() {}

    private Review(Long memberId, Long productId, Long orderItemId, short rating, String title, String body) {
        this.memberId = memberId;
        this.productId = productId;
        this.orderItemId = orderItemId;
        this.rating = rating;
        this.title = title;
        this.body = body;
    }

    public static Review create(Long memberId, Long productId, Long orderItemId, short rating, String title, String body) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        return new Review(memberId, productId, orderItemId, rating, title, body);
    }

    public void hide() {
        this.status = ReviewStatus.HIDDEN.name();
    }

    public void addImage(ReviewImage image) {
        this.images.add(image);
    }

    // Getters
    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public Long getProductId() { return productId; }
    public Long getOrderItemId() { return orderItemId; }
    public Short getRating() { return rating; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public ReviewStatus getStatus() { return ReviewStatus.valueOf(status); }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public List<ReviewImage> getImages() { return images; }
    public List<ReviewReport> getReports() { return reports; }

    /**
     * 리뷰 상태 (PRD §6.10).
     */
    public enum ReviewStatus {
        VISIBLE,  // 공개
        HIDDEN    // 관리자 숨김
    }
}
