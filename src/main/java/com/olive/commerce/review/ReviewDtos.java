package com.olive.commerce.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 리뷰 도메인 DTO (PRD §6.10).
 */
public final class ReviewDtos {

    private ReviewDtos() {}

    /**
     * 리뷰 작성 요청.
     *
     * @param orderItemId 주문 상품 ID (배송 완료된 것만 가능)
     * @param rating      평점 (1-5)
     * @param title       제목 (선택)
     * @param body        내용
     * @param imageUrls   이미지 URL 목록 (선택, S3 presigned URL)
     */
    public record WriteReviewRequest(
        @NotNull(message = "orderItemId는 필수입니다")
        Long orderItemId,

        @NotNull(message = "rating은 필수입니다")
        @Min(value = 1, message = "rating은 1 이상이어야 합니다")
        @Max(value = 5, message = "rating은 5 이하이어야 합니다")
        Integer rating,

        @Size(max = 255, message = "title은 255자 이하이어야 합니다")
        String title,

        @NotBlank(message = "body는 필수입니다")
        @Size(max = 5000, message = "body는 5000자 이하이어야 합니다")
        String body,

        List<String> imageUrls
    ) {
        public Short ratingAsShort() {
            return rating.shortValue();
        }
    }

    /**
     * 리뷰 작성 응답.
     *
     * @param id        작성된 리뷰 ID
     * @param productId 리뷰된 상품 ID
     * @param rating    평점
     * @param createdAt 작성일시
     */
    public record WriteReviewResponse(
        Long id,
        Long productId,
        Short rating,
        OffsetDateTime createdAt
    ) {}

    /**
     * 리뷰 목록 응답 (공개 API).
     *
     * @param id           리뷰 ID
     * @param productId    상품 ID
     * @param rating       평점
     * @param title        제목
     * @param body         내용
     * @param imageUrls    이미지 URL 목록
     * @param createdAt    작성일시
     */
    public record ReviewListResponse(
        Long id,
        Long productId,
        Short rating,
        String title,
        String body,
        List<String> imageUrls,
        OffsetDateTime createdAt
    ) {}

    /**
     * 내 리뷰 목록 응답.
     *
     * @param id           리뷰 ID
     * @param productId    상품 ID
     * @param productName  상품명 (snapshot)
     * @param rating       평점
     * @param title        제목
     * @param body         내용
     * @param status       VISIBLE/HIDDEN
     * @param createdAt    작성일시
     */
    public record MyReviewListResponse(
        Long id,
        Long productId,
        String productName,
        Short rating,
        String title,
        String body,
        String status,
        OffsetDateTime createdAt
    ) {}

    /**
     * 리뷰 상세 응답.
     *
     * @param id           리뷰 ID
     * @param productId    상품 ID
     * @param productName  상품명
     * @param rating       평점
     * @param title        제목
     * @param body         내용
     * @param imageUrls    이미지 URL 목록
     * @param status       VISIBLE/HIDDEN
     * @param createdAt    작성일시
     */
    public record ReviewDetailResponse(
        Long id,
        Long productId,
        String productName,
        Short rating,
        String title,
        String body,
        List<String> imageUrls,
        String status,
        OffsetDateTime createdAt
    ) {}

    /**
     * 리뷰 신고 요청.
     *
     * @param reason 신고 사유
     */
    public record ReportReviewRequest(
        @NotBlank(message = "reason은 필수입니다")
        @Size(max = 255, message = "reason은 255자 이하이어야 합니다")
        String reason
    ) {}

    /**
     * 리뷰 신고 응답.
     *
     * @param id       신고 ID
     * @param reviewId 리뷰 ID
     * @param status   신고 상태 (PENDING)
     */
    public record ReportReviewResponse(
        Long id,
        Long reviewId,
        String status
    ) {}

    /**
     * 관리자: 신고 목록 응답.
     *
     * @param id               신고 ID
     * @param reviewId         리뷰 ID
     * @param reporterMemberId 신고자 회원 ID
     * @param reason           신고 사유
     * @param status           처리 상태
     * @param createdAt        신고일시
     */
    public record ReviewReportAdminResponse(
        Long id,
        Long reviewId,
        Long reporterMemberId,
        String reason,
        String status,
        OffsetDateTime createdAt
    ) {}

    /**
     * 관리자: 리뷰 숨김 응답.
     *
     * @param id     리뷰 ID
     * @param status 변경된 상태 (HIDDEN)
     */
    public record HideReviewResponse(
        Long id,
        String status
    ) {}
}
