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
 * 리뷰 신고 엔티티 (PRD §6.10).
 */
@Entity
@Table(name = "review_reports")
public class ReviewReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Column(name = "reporter_member_id", nullable = false)
    private Long reporterMemberId;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "status", nullable = false, length = 20)
    private String status = ReportStatus.PENDING.name();

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected ReviewReport() {}

    private ReviewReport(Review review, Long reporterMemberId, String reason) {
        this.review = review;
        this.reporterMemberId = reporterMemberId;
        this.reason = reason;
    }

    public static ReviewReport create(Review review, Long reporterMemberId, String reason) {
        return new ReviewReport(review, reporterMemberId, reason);
    }

    public void resolve() {
        this.status = ReportStatus.RESOLVED.name();
    }

    public void dismiss() {
        this.status = ReportStatus.DISMISSED.name();
    }

    // Getters
    public Long getId() { return id; }
    public Review getReview() { return review; }
    public Long getReporterMemberId() { return reporterMemberId; }
    public String getReason() { return reason; }
    public ReportStatus getStatus() { return ReportStatus.valueOf(status); }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /**
     * 신고 상태 (PRD §6.10).
     */
    public enum ReportStatus {
        PENDING,   // 관리자 처리 대기
        RESOLVED,  // 해결됨 (리뷰 숨김 등 조치 취함)
        DISMISSED  // 기각함 (부정 신고)
    }
}
