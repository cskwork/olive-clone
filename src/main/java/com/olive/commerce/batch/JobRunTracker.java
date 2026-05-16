package com.olive.commerce.batch;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배치 작업 실행 추적 유틸리티.
 * <p>작업 시작/완료/실패 기록을 JobRun 테이블에 저장합니다.
 */
@Component
@RequiredArgsConstructor
public class JobRunTracker {

    private static final Logger log = LoggerFactory.getLogger(JobRunTracker.class);

    private final JobRunRepository repository;

    /**
     * 새 작업 실행을 시작합니다.
     *
     * @param jobName 작업 이름
     * @param triggeredBy 실행 트리거
     * @return JobRun 엔티티
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JobRun start(String jobName, JobRun.TriggeredBy triggeredBy) {
        JobRun jobRun = JobRun.start(jobName, triggeredBy);
        JobRun saved = repository.save(jobRun);
        log.info("[{}] Job run started: id={}", jobName, saved.getId());
        return saved;
    }

    /**
     * 작업을 완료 상태로 표시합니다.
     *
     * @param jobRun 작업 실행
     * @param processedCount 처리 건수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(JobRun jobRun, int processedCount) {
        jobRun.complete(processedCount);
        repository.save(jobRun);
        log.info("[{}] Job run completed: id={}, processed={}", jobRun.getJobName(), jobRun.getId(), processedCount);
    }

    /**
     * 작업을 실패 상태로 표시합니다.
     *
     * @param jobRun 작업 실행
     * @param errorMessage 에러 메시지
     * @param processedCount 처리 건수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(JobRun jobRun, String errorMessage, int processedCount) {
        jobRun.fail(errorMessage, processedCount);
        repository.save(jobRun);
        log.error("[{}] Job run failed: id={}, processed={}, error={}", jobRun.getJobName(), jobRun.getId(), processedCount, errorMessage);
    }

    /**
     * 작업 실행을 조회합니다.
     *
     * @param id JobRun ID
     * @return JobRun 엔티티
     */
    public JobRun get(Long id) {
        return repository.findById(id).orElse(null);
    }
}
