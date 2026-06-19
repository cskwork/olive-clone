package com.olive.commerce.batch;

import com.olive.commerce.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 배치 작업 관리자 API (OLV-120).
 * <p>경로: {@code /api/admin/jobs}
 * <p>권한: {@code SUPER_ADMIN}
 */
@RestController
@RequestMapping("/api/admin/jobs")
@RequiredArgsConstructor
public class JobAdminController {

    private static final Logger log = LoggerFactory.getLogger(JobAdminController.class);

    private final JobExecutionService jobExecutionService;
    private final JobRunRepository jobRunRepository;

    /**
     * 작업을 수동으로 실행합니다.
     *
     * @param jobName 작업 이름
     * @return 실행 결과
     */
    @PostMapping("/{jobName}/run-now")
    public ResponseEntity<ApiResponse<JobDtos.ManualRunResponse>> runNow(
            @PathVariable String jobName
    ) {
        try {
            JobRun jobRun = jobExecutionService.execute(jobName);
            return ResponseEntity.ok(ApiResponse.success(JobDtos.ManualRunResponse.started(jobRun)));
        } catch (IllegalArgumentException e) {
            log.warn("Job not found: jobName={} detail={}", jobName, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(JobDtos.ManualRunResponse.failed(jobName, "Unknown job name")));
        } catch (JobExecutionService.JobExecutionException e) {
            log.error("Job execution failed: jobName={}", jobName, e);
            return ResponseEntity.ok(ApiResponse.success(JobDtos.ManualRunResponse.failed(jobName, "Job execution failed")));
        } catch (Exception e) {
            log.error("Unexpected error running job: jobName={}", jobName, e);
            return ResponseEntity.ok(ApiResponse.success(JobDtos.ManualRunResponse.failed(jobName, "Unexpected error")));
        }
    }

    /**
     * 작업 실행 기록을 조회합니다.
     *
     * @param jobName 작업 이름 (선택)
     * @param limit   최대 결과 수 (기본 100)
     * @return 실행 기록 목록
     */
    @GetMapping("/runs")
    public ResponseEntity<ApiResponse<JobDtos.JobRunListResponse>> getRuns(
            @RequestParam(required = false) String jobName,
            @RequestParam(defaultValue = "100") int limit
    ) {
        List<JobRun> runs;
        if (jobName != null && !jobName.isBlank()) {
            runs = jobRunRepository.findByJobNameOrderByStartedAtDesc(jobName);
        } else {
            runs = jobRunRepository.findAllByOrderByStartedAtDesc();
        }
        return ResponseEntity.ok(ApiResponse.success(JobDtos.JobRunListResponse.of(runs.stream()
                .limit(limit)
                .toList())));
    }

    /**
     * 최근 작업 실행 기록을 조회합니다.
     *
     * @param jobName 작업 이름
     * @return 최신 실행 기록
     */
    @GetMapping("/{jobName}/latest")
    public ResponseEntity<ApiResponse<JobDtos.JobRunResponse>> getLatestRun(
            @PathVariable String jobName
    ) {
        return jobRunRepository.findTopByJobNameOrderByStartedAtDesc(jobName)
                .map(run -> ResponseEntity.ok(ApiResponse.success(JobDtos.JobRunResponse.from(run))))
                .orElse(ResponseEntity.ok(ApiResponse.success(null)));
    }
}
