package com.olive.commerce.review;

import org.springframework.context.ApplicationEvent;

/**
 * 리뷰 작성 이벤트.
 * <p>
 * 리뷰가 작성되면 발행되어 상품 리뷰 요약(product_review_summaries)을 갱신합니다.
 */
public class ReviewCreatedEvent extends ApplicationEvent {

    private final Long reviewId;
    private final Long productId;
    private final Long memberId;
    private final Short rating;

    public ReviewCreatedEvent(Object source, Long reviewId, Long productId, Long memberId, Short rating) {
        super(source);
        this.reviewId = reviewId;
        this.productId = productId;
        this.memberId = memberId;
        this.rating = rating;
    }

    public Long reviewId() { return reviewId; }
    public Long productId() { return productId; }
    public Long memberId() { return memberId; }
    public Short rating() { return rating; }
}
