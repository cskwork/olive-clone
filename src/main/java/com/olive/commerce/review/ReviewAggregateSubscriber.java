package com.olive.commerce.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 리뷰 집계 구독자 (PRD §6.10).
 * <p>
 * ReviewCreatedEvent를 수신하여 product_review_summaries 테이블을 갱신합니다.
 * AFTER_COMMIT 페이즈에 실행되어 리뷰 트랜잭션이 커밋된 후에만 집계를 업데이트합니다.
 */
@Component
public class ReviewAggregateSubscriber {

    private static final Logger log = LoggerFactory.getLogger(ReviewAggregateSubscriber.class);

    private final ProductReviewSummaryRepository summaryRepository;

    public ReviewAggregateSubscriber(ProductReviewSummaryRepository summaryRepository) {
        this.summaryRepository = summaryRepository;
    }

    /**
     * 리뷰 작성 이벤트를 수신하여 상품 리뷰 요약을 갱신합니다.
     * <p>
     * PostgreSQL ON CONFLICT DO UPDATE 패턴을 JPA로 구현합니다.
     *
     * @param event 리뷰 작성 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCreated(ReviewCreatedEvent event) {
        log.info("ReviewCreatedEvent received: reviewId={}, productId={}, rating={}",
            event.reviewId(), event.productId(), event.rating());

        try {
            ProductReviewSummary summary = summaryRepository
                .findByProductId(event.productId())
                .orElseGet(() -> ProductReviewSummary.create(event.productId()));

            summary.addReview(event.rating());
            summaryRepository.save(summary);

            log.info("Product review summary updated: productId={}, avgRating={}, reviewCount={}",
                event.productId(), summary.getAvgRating(), summary.getReviewCount());

        } catch (Exception e) {
            log.error("Failed to update product review summary: productId={}", event.productId(), e);
            // TODO: 재시도 로직 또는 DLQ 처리
        }
    }
}
