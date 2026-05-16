package com.olive.commerce.review;

import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.order.OrderItem;
import com.olive.commerce.order.OrderItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 리뷰 서비스 (PRD §6.10).
 */
@Service
@Transactional
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewRepository reviewRepository;
    private final ReviewImageRepository reviewImageRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final ProductReviewSummaryRepository productReviewSummaryRepository;
    private final OrderItemRepository orderItemRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ReviewService(
            ReviewRepository reviewRepository,
            ReviewImageRepository reviewImageRepository,
            ReviewReportRepository reviewReportRepository,
            ProductReviewSummaryRepository productReviewSummaryRepository,
            OrderItemRepository orderItemRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.reviewRepository = reviewRepository;
        this.reviewImageRepository = reviewImageRepository;
        this.reviewReportRepository = reviewReportRepository;
        this.productReviewSummaryRepository = productReviewSummaryRepository;
        this.orderItemRepository = orderItemRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 리뷰 작성 (PRD §6.10).
     * <p>
     * 자격 검증: order_item이 속한 주문의 소유자이며 상태가 DELIVERED여야 합니다.
     * 중복 방지: order_item_id UNIQUE 제약으로 하나의 주문 상품당 하나의 리뷰만 작성 가능합니다.
     *
     * @param memberId 작성자 회원 ID
     * @param request  리뷰 작성 요청
     * @return 작성된 리뷰 정보
     * @throws BusinessException REVIEW_ELIGIBLE_ORDER_REQUIRED - 배송 완료 전 리뷰 작성 시도
     * @throws BusinessException REVIEW_ALREADY_EXISTS - 이미 리뷰 작성됨
     */
    public ReviewDtos.WriteReviewResponse writeReview(Long memberId, ReviewDtos.WriteReviewRequest request) {
        // 자격 검증: 배송 완료 확인
        if (!reviewRepository.isEligibleForReview(memberId, request.orderItemId())) {
            throw new BusinessException(ErrorCode.REVIEW_ELIGIBLE_ORDER_REQUIRED,
                "orderItemId=" + request.orderItemId() + " is not eligible for review (not delivered or not owned)");
        }

        // 중복 확인
        if (reviewRepository.existsByMemberIdAndOrderItemId(memberId, request.orderItemId())) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS,
                "Review already exists for orderItemId=" + request.orderItemId());
        }

        // OrderItem에서 productId 가져오기
        OrderItem orderItem = orderItemRepository.findById(request.orderItemId())
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                "OrderItem not found: id=" + request.orderItemId()));
        Long productId = orderItem.getProductId();

        // 리뷰 생성
        Review review = Review.create(
            memberId,
            productId,
            request.orderItemId(),
            request.ratingAsShort(),
            request.title(),
            request.body()
        );
        Review savedReview = reviewRepository.save(review);

        // 이미지 저장
        if (request.imageUrls() != null && !request.imageUrls().isEmpty()) {
            int sortOrder = 0;
            for (String imageUrl : request.imageUrls()) {
                ReviewImage image = ReviewImage.create(savedReview, imageUrl, sortOrder++);
                savedReview.addImage(image);
                reviewImageRepository.save(image);
            }
        }

        // 이벤트 발행 (집계 테이블 갱신 트리거)
        eventPublisher.publishEvent(new ReviewCreatedEvent(
            this,
            savedReview.getId(),
            savedReview.getProductId(),
            savedReview.getMemberId(),
            savedReview.getRating()
        ));

        log.info("Review created: id={}, memberId={}, productId={}, rating={}",
            savedReview.getId(), memberId, productId, savedReview.getRating());

        return new ReviewDtos.WriteReviewResponse(
            savedReview.getId(),
            savedReview.getProductId(),
            savedReview.getRating(),
            savedReview.getCreatedAt()
        );
    }

    /**
     * 상품별 공개 리뷰 목록 조회 (PRD §6.10).
     * <p>
     * VISIBLE 상태의 리뷰만 조회합니다.
     *
     * @param productId 상품 ID
     * @param sort      정렬 (latest | helpful) - 현재 latest만 지원
     * @param page      페이지 번호 (0-based)
     * @param size      페이지 크기
     * @return 리뷰 목록
     */
    @Transactional(readOnly = true)
    public Page<ReviewDtos.ReviewListResponse> getProductReviews(Long productId, String sort, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Review> reviews = reviewRepository.findVisibleByProductId(productId, pageable);

        return reviews.map(review -> {
            List<String> imageUrls = review.getImages().stream()
                .map(ReviewImage::getUrl)
                .toList();

            return new ReviewDtos.ReviewListResponse(
                review.getId(),
                review.getProductId(),
                review.getRating(),
                review.getTitle(),
                review.getBody(),
                imageUrls,
                review.getCreatedAt()
            );
        });
    }

    /**
     * 내 리뷰 목록 조회.
     *
     * @param memberId 회원 ID
     * @param page     페이지 번호
     * @param size     페이지 크기
     * @return 내 리뷰 목록
     */
    @Transactional(readOnly = true)
    public Page<ReviewDtos.MyReviewListResponse> getMyReviews(Long memberId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Review> reviews = reviewRepository.findByMemberIdOrderByCreatedAtDesc(memberId, pageable);

        return reviews.map(review -> {
            // TODO: Product 테이블에서 productName 가져오기
            // 임시로 상품 ID를 문자열로 사용
            String productName = "Product #" + review.getProductId();

            return new ReviewDtos.MyReviewListResponse(
                review.getId(),
                review.getProductId(),
                productName,
                review.getRating(),
                review.getTitle(),
                review.getBody(),
                review.getStatus().name(),
                review.getCreatedAt()
            );
        });
    }

    /**
     * 리뷰 신고 (PRD §6.10).
     *
     * @param reviewId 리뷰 ID
     * @param memberId 신고자 회원 ID
     * @param request  신고 요청
     * @return 신고 결과
     * @throws BusinessException REVIEW_NOT_FOUND - 리뷰 없음
     * @throws BusinessException 리뷰 작성자는 자신의 리뷰를 신고할 수 없음
     */
    public ReviewDtos.ReportReviewResponse reportReview(Long reviewId, Long memberId, ReviewDtos.ReportReviewRequest request) {
        Review review = reviewRepository.findById(reviewId)
            .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND,
                "Review not found: id=" + reviewId));

        // 자신의 리뷰는 신고 불가
        if (review.getMemberId().equals(memberId)) {
            throw new BusinessException(ErrorCode.REVIEW_SELF_REPORT_NOT_ALLOWED,
                "Cannot report your own review");
        }

        // 이미 신고했는지 확인
        if (reviewReportRepository.existsByReviewIdAndReporterMemberId(reviewId, memberId)) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_REPORTED,
                "Already reported this review");
        }

        ReviewReport report = ReviewReport.create(review, memberId, request.reason());
        ReviewReport savedReport = reviewReportRepository.save(report);

        log.info("Review reported: reportId={}, reviewId={}, reporterId={}",
            savedReport.getId(), reviewId, memberId);

        return new ReviewDtos.ReportReviewResponse(
            savedReport.getId(),
            reviewId,
            savedReport.getStatus().name()
        );
    }

    /**
     * 관리자: 리뷰 숨김 (PRD §6.10).
     *
     * @param reviewId 리뷰 ID
     * @return 숨김 처리 결과
     * @throws BusinessException REVIEW_NOT_FOUND - 리뷰 없음
     */
    public ReviewDtos.HideReviewResponse hideReview(Long reviewId) {
        Review review = reviewRepository.findById(reviewId)
            .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND,
                "Review not found: id=" + reviewId));

        if (review.getStatus() == Review.ReviewStatus.HIDDEN) {
            // 이미 숨김 상태면 no-op
            return new ReviewDtos.HideReviewResponse(review.getId(), review.getStatus().name());
        }

        review.hide();
        Review savedReview = reviewRepository.save(review);

        productReviewSummaryRepository.findByProductId(savedReview.getProductId())
            .ifPresent(summary -> {
                summary.removeReview(savedReview.getRating());
                productReviewSummaryRepository.save(summary);
            });

        log.info("Review hidden: id={}", reviewId);

        return new ReviewDtos.HideReviewResponse(
            savedReview.getId(),
            savedReview.getStatus().name()
        );
    }

    /**
     * 관리자: 신고 목록 조회.
     *
     * @param status 상태 필터 (PENDING | RESOLVED | DISMISSED)
     * @param page   페이지 번호
     * @param size   페이지 크기
     * @return 신고 목록
     */
    @Transactional(readOnly = true)
    public Page<ReviewDtos.ReviewReportAdminResponse> getReviewReports(String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        ReviewReport.ReportStatus reportStatus = status != null
            ? ReviewReport.ReportStatus.valueOf(status)
            : ReviewReport.ReportStatus.PENDING;

        Page<ReviewReport> reports = reviewReportRepository.findByStatus(reportStatus, pageable);

        return reports.map(report ->
            new ReviewDtos.ReviewReportAdminResponse(
                report.getId(),
                report.getReview().getId(),
                report.getReporterMemberId(),
                report.getReason(),
                report.getStatus().name(),
                report.getCreatedAt()
            )
        );
    }
}
