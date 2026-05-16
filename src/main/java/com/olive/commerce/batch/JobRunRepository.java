package com.olive.commerce.batch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 배치 작업 실행 기록 Repository.
 */
@Repository
public interface JobRunRepository extends JpaRepository<JobRun, Long> {

    /**
     * 특정 작업의 최신 실행 기록을 조회합니다.
     *
     * @param jobName 작업 이름
     * @return 최신 실행 기록
     */
    @Query("SELECT j FROM JobRun j WHERE j.jobName = :jobName ORDER BY j.startedAt DESC")
    List<JobRun> findByJobNameOrderByStartedAtDesc(@Param("jobName") String jobName);

    /**
     * 특정 작업의 최신 실행 기록 1건을 조회합니다.
     *
     * @param jobName 작업 이름
     * @return 최신 실행 기록
     */
    @Query("SELECT j FROM JobRun j WHERE j.jobName = :jobName ORDER BY j.startedAt DESC LIMIT 1")
    Optional<JobRun> findLatestByJobName(@Param("jobName") String jobName);

    /**
     * 실패한 작업 실행 기록을 조회합니다.
     *
     * @param since 기준 시간 이후
     * @return 실패한 실행 기록 목록
     */
    @Query("SELECT j FROM JobRun j WHERE j.status = 'FAILED' AND j.startedAt > :since ORDER BY j.startedAt DESC")
    List<JobRun> findFailedRunsSince(@Param("since") OffsetDateTime since);

    /**
     * 특정 작업이 실행 중인지 확인합니다.
     *
     * @param jobName 작업 이름
     * @return 실행 중인 작업 실행 기록
     */
    @Query("SELECT j FROM JobRun j WHERE j.jobName = :jobName AND j.status = 'STARTED' ORDER BY j.startedAt DESC")
    List<JobRun> findRunningByJobName(@Param("jobName") String jobName);

    /**
     * 특정 작업의 최신 실행 기록 1건을 조회합니다 (Admin API용).
     *
     * @param jobName 작업 이름
     * @return 최신 실행 기록
     */
    Optional<JobRun> findTopByJobNameOrderByStartedAtDesc(String jobName);

    /**
     * 전체 작업 실행 기록을 최신순으로 조회합니다 (Admin API용).
     *
     * @return 전체 실행 기록
     */
    List<JobRun> findAllByOrderByStartedAtDesc();
}
