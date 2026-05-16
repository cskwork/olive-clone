package com.olive.commerce.review;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리뷰 컨트롤러 (PRD §6.10).
 * <p>
 * 리뷰 작성, 조회, 신고 엔드포인트를 제공합니다.
 */
@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 리뷰 작성 (PRD §6.10).
     * <p>
     * 배송 완료(DELIVERED)된 주문 상품에 대해서만 리뷰를 작성할 수 있습니다.
     *
     * @param request  리뷰 작성 요청
     * @param principal 인증된 회원
     * @return 작성된 리뷰 정보
     */
    @PostMapping("/me/reviews")
    public ResponseEntity<ApiResponse<ReviewDtos.WriteReviewResponse>> writeReview(
            @Valid @RequestBody ReviewDtos.WriteReviewRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Long memberId = principal.memberId();
        ReviewDtos.WriteReviewResponse response = reviewService.writeReview(memberId, request);

        return ResponseEntity
                .status(org.springframework.http.HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    /**
     * 상품별 리뷰 목록 조회 (PRD §6.10).
     * <p>
     * 공개 API입니다. VISIBLE 상태의 리뷰만 조회합니다.
     *
     * @param productId 상품 ID
     * @param sort      정렬 (latest | helpful)
     * @param page      페이지 번호 (0-based)
     * @param size      페이지 크기
     * @return 리뷰 목록
     */
    @GetMapping("/products/{productId}/reviews")
    public ResponseEntity<ApiResponse<java.util.List<ReviewDtos.ReviewListResponse>>> getProductReviews(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<ReviewDtos.ReviewListResponse> result = reviewService.getProductReviews(productId, sort, page, size);

        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ResponseEntity.ok(ApiResponse.success(result.getContent(), meta));
    }

    /**
     * 내 리뷰 목록 조회.
     *
     * @param page     페이지 번호
     * @param size     페이지 크기
     * @param principal 인증된 회원
     * @return 내 리뷰 목록
     */
    @GetMapping("/me/reviews")
    public ResponseEntity<ApiResponse<java.util.List<ReviewDtos.MyReviewListResponse>>> getMyReviews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Long memberId = principal.memberId();
        Page<ReviewDtos.MyReviewListResponse> result = reviewService.getMyReviews(memberId, page, size);

        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ResponseEntity.ok(ApiResponse.success(result.getContent(), meta));
    }

    /**
     * 리뷰 신고 (PRD §6.10).
     *
     * @param reviewId 리뷰 ID
     * @param request  신고 요청
     * @param principal 인증된 회원
     * @return 신고 결과
     */
    @PostMapping("/me/reviews/{reviewId}/report")
    public ResponseEntity<ApiResponse<ReviewDtos.ReportReviewResponse>> reportReview(
            @PathVariable Long reviewId,
            @Valid @RequestBody ReviewDtos.ReportReviewRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        Long memberId = principal.memberId();
        ReviewDtos.ReportReviewResponse response = reviewService.reportReview(reviewId, memberId, request);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
