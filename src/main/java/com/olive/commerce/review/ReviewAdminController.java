package com.olive.commerce.review;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.member.MemberRole;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리뷰 관리자 컨트롤러 (PRD §6.10).
 * <p>
 * CS_MANAGER 역할 이상만 접근 가능합니다.
 */
@RestController
@RequestMapping("/api/admin")
@Secured("ROLE_CS_MANAGER")
public class ReviewAdminController {

    private final ReviewService reviewService;

    public ReviewAdminController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 리뷰 신고 목록 조회.
     *
     * @param status 상태 필터 (PENDING | RESOLVED | DISMISSED)
     * @param page   페이지 번호
     * @param size   페이지 크기
     * @return 신고 목록
     */
    @GetMapping("/review-reports")
    public ResponseEntity<ApiResponse<java.util.List<ReviewDtos.ReviewReportAdminResponse>>> getReviewReports(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<ReviewDtos.ReviewReportAdminResponse> result = reviewService.getReviewReports(status, page, size);

        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ResponseEntity.ok(ApiResponse.success(result.getContent(), meta));
    }

    /**
     * 리뷰 숨김 처리.
     *
     * @param reviewId 리뷰 ID
     * @return 숨김 처리 결과
     */
    @PostMapping("/reviews/{reviewId}/hide")
    public ResponseEntity<ApiResponse<ReviewDtos.HideReviewResponse>> hideReview(
            @PathVariable Long reviewId
    ) {
        ReviewDtos.HideReviewResponse response = reviewService.hideReview(reviewId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
