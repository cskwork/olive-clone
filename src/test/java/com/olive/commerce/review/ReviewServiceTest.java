package com.olive.commerce.review;

import com.olive.commerce.order.OrderItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewServiceTest {

    @Test
    void hideReview_removesReviewFromProductSummary() {
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        ReviewImageRepository imageRepository = mock(ReviewImageRepository.class);
        ReviewReportRepository reportRepository = mock(ReviewReportRepository.class);
        ProductReviewSummaryRepository summaryRepository = mock(ProductReviewSummaryRepository.class);
        OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ReviewService service = new ReviewService(
            reviewRepository,
            imageRepository,
            reportRepository,
            summaryRepository,
            orderItemRepository,
            eventPublisher
        );
        Review review = Review.create(1L, 10L, 100L, (short) 5, "좋아요", "본문");
        ProductReviewSummary summary = ProductReviewSummary.create(10L);
        summary.addReview((short) 5);

        when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(summaryRepository.findByProductId(10L)).thenReturn(Optional.of(summary));

        service.hideReview(1L);

        assertThat(summary.getReviewCount()).isZero();
        assertThat(summary.getAvgRating()).isZero();
        verify(summaryRepository).save(summary);
    }
}
