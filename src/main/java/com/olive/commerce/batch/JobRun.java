package com.olive.commerce.batch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 배치 작업 실행 기록 엔티티 (PRD §17).
 * <p>모든 스케줄/수동 실행 기록을 저장하여 운영 가시성을 확보합니다.
 */
@Entity
@Table(name = "job_runs")
public class JobRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false, length = 100)
    private String jobName;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "processed_count", nullable = false)
    private Integer processedCount;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "triggered_by", nullable = false, length = 50)
    private String triggeredBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected JobRun() {}

    private JobRun(String jobName, TriggeredBy triggeredBy) {
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be blank");
        }
        this.jobName = jobName;
        this.startedAt = OffsetDateTime.now();
        this.status = Status.STARTED.name();
        this.processedCount = 0;
        this.triggeredBy = triggeredBy.name();
    }

    /**
     * 새로운 작업 실행을 시작합니다.
     */
    public static JobRun start(String jobName, TriggeredBy triggeredBy) {
        return new JobRun(jobName, triggeredBy);
    }

    /**
     * 작업을 완료 상태로 표시합니다.
     */
    public void complete(int processedCount) {
        if (getStatus() != Status.STARTED) {
            throw new IllegalStateException("Job already finished: " + status);
        }
        this.finishedAt = OffsetDateTime.now();
        this.status = Status.COMPLETED.name();
        this.processedCount = processedCount;
    }

    /**
     * 작업을 실패 상태로 표시합니다.
     */
    public void fail(String errorMessage, int processedCount) {
        if (getStatus() != Status.STARTED) {
            throw new IllegalStateException("Job already finished: " + status);
        }
        this.finishedAt = OffsetDateTime.now();
        this.status = Status.FAILED.name();
        this.errorMessage = errorMessage;
        this.processedCount = processedCount;
    }

    /**
     * 처리 카운트를 증가시킵니다.
     */
    public void incrementProcessed() {
        this.processedCount++;
    }

    /**
     * 처리 카운트를 설정합니다.
     */
    public void setProcessedCount(int count) {
        this.processedCount = count;
    }

    // Getters
    public Long getId() { return id; }
    public String getJobName() { return jobName; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public Status getStatus() { return Status.valueOf(status); }
    public Integer getProcessedCount() { return processedCount; }
    public String getErrorMessage() { return errorMessage; }
    public TriggeredBy getTriggeredBy() { return TriggeredBy.valueOf(triggeredBy); }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /**
     * 작업 상태 (PRD §17).
     */
    public enum Status {
        STARTED,    // 실행 중
        COMPLETED,  // 완료
        FAILED      // 실패
    }

    /**
     * 실행 트리거 (PRD §17).
     */
    public enum TriggeredBy {
        SCHEDULED,  // 스케줄러에 의한 자동 실행
        MANUAL      // 관리자 API에 의한 수동 실행
    }
}
