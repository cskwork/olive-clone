package com.olive.commerce.batch;

import java.time.OffsetDateTime;

/**
 * 배치 작업 DTO (OLV-120).
 */
public class JobDtos {

    /**
     * 작업 실행 기록 응답.
     */
    public record JobRunResponse(
            Long id,
            String jobName,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String status,
            Integer processedCount,
            String errorMessage,
            String triggeredBy
    ) {
        public static JobRunResponse from(JobRun jobRun) {
            return new JobRunResponse(
                    jobRun.getId(),
                    jobRun.getJobName(),
                    jobRun.getStartedAt(),
                    jobRun.getFinishedAt(),
                    jobRun.getStatus().name(),
                    jobRun.getProcessedCount(),
                    jobRun.getErrorMessage(),
                    jobRun.getTriggeredBy().name()
            );
        }
    }

    /**
     * 수동 실행 요청 응답.
     */
    public record ManualRunResponse(
            Long jobRunId,
            String jobName,
            String status,
            String message
    ) {
        public static ManualRunResponse started(JobRun jobRun) {
            return new ManualRunResponse(
                    jobRun.getId(),
                    jobRun.getJobName(),
                    "STARTED",
                    "Job execution started"
            );
        }

        public static ManualRunResponse failed(String jobName, String error) {
            return new ManualRunResponse(
                    null,
                    jobName,
                    "FAILED",
                    error
            );
        }
    }

    /**
     * 작업 실행 목록 응답.
     */
    public record JobRunListResponse(
            java.util.List<JobRunResponse> runs,
            int total
    ) {
        public static JobRunListResponse of(java.util.List<JobRun> runs) {
            return new JobRunListResponse(
                    runs.stream()
                        .map(JobRunResponse::from)
                        .toList(),
                    runs.size()
            );
        }
    }
}
