package com.olive.commerce.batch;

import com.olive.commerce.promotion.PointHistory;
import com.olive.commerce.promotion.PointHistory.ChangeType;
import com.olive.commerce.promotion.PointHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 포인트 만료 배치 작업 (PRD §17).
 * <p>
 * 매일 00:00 실행: 만료일이 지난 EARN 상태의 사용 가능 포인트에 대해 EXPIRE 내역을 생성합니다.
 */
@Component
@RequiredArgsConstructor
public class PointExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PointExpiryJob.class);

    private static final String JOB_NAME = "pointExpiry";

    private final JobRunTracker jobRunTracker;
    private final PointHistoryRepository pointHistoryRepository;

    /**
     * 포인트 만료 처리 (매일 00:00).
     */
    @Scheduled(cron = "0 0 0 * * *")
    @SchedulerLock(name = "PointExpiryJob", lockAtMostFor = "55m", lockAtLeastFor = "1m")
    public void expirePoints() {
        JobRun jobRun = jobRunTracker.start(JOB_NAME, JobRun.TriggeredBy.SCHEDULED);
        int processedCount = 0;
        String errorMessage = null;

        try {
            processedCount = expireEarnedPoints();
            jobRunTracker.complete(jobRun, processedCount);

        } catch (Exception e) {
            errorMessage = e.getMessage();
            jobRunTracker.fail(jobRun, errorMessage, processedCount);
            log.error("[{}] Job execution failed: {}", JOB_NAME, e.getMessage(), e);
        }
    }

    /**
     * 만료된 적립 포인트에 대해 EXPIRE 내역을 생성합니다.
     *
     * @return 생성된 EXPIRE 내역 수
     */
    @Transactional
    public int expireEarnedPoints() {
        OffsetDateTime now = OffsetDateTime.now();

        // 만료일이 지난 EARN 내역 조회
        List<PointHistory> expiredEarns = pointHistoryRepository.findExpiredEarns(now);
        int count = expiredEarns.size();

        for (PointHistory earn : expiredEarns) {
            // EXPIRE 내역 생성 (원본 내역의 ID를 reason에 포함하여 추적 가능)
            PointHistory expire = PointHistory.expire(
                    earn.getMemberId(),
                    earn.getAmount(),
                    "만료: 원본 내역 ID " + earn.getId(),
                    earn.getId()
            );
            pointHistoryRepository.save(expire);
        }

        if (count > 0) {
            log.info("[{}] Created {} EXPIRE records for expired points", JOB_NAME, count);
        }

        return count;
    }
}
