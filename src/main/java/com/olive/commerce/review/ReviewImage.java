package com.olive.commerce.review;

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
 * 리뷰 이미지 엔티티 (PRD §6.10).
 */
@Entity
@Table(name = "review_images")
public class ReviewImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected ReviewImage() {}

    private ReviewImage(Review review, String url, int sortOrder) {
        this.review = review;
        this.url = url;
        this.sortOrder = sortOrder;
    }

    public static ReviewImage create(Review review, String url, int sortOrder) {
        return new ReviewImage(review, url, sortOrder);
    }

    // Getters
    public Long getId() { return id; }
    public Review getReview() { return review; }
    public String getUrl() { return url; }
    public int getSortOrder() { return sortOrder; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
