package com.olive.commerce.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 리뷰 신고 리포지토리.
 */
public interface ReviewReportRepository extends JpaRepository<ReviewReport, Long> {

    /**
     * 상태별 신고 목록 조회 (최신순).
     */
    @Query("SELECT r FROM ReviewReport r WHERE r.status = :status ORDER BY r.createdAt DESC")
    Page<ReviewReport> findByStatus(@Param("status") ReviewReport.ReportStatus status, Pageable pageable);

    /**
     * 리뷰별 신고 목록 조회.
     */
    Page<ReviewReport> findByReviewId(Long reviewId, Pageable pageable);

    /**
     * 회원이 특정 리뷰를 이미 신고했는지 확인.
     */
    boolean existsByReviewIdAndReporterMemberId(Long reviewId, Long reporterMemberId);

    /**
     * 리뷰 ID와 신고자 ID로 신고 조회.
     */
    Optional<ReviewReport> findByReviewIdAndReporterMemberId(Long reviewId, Long reporterMemberId);
}
