package com.olive.commerce.batch;

import com.olive.commerce.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 재고 선점 만료 해제 배치 작업 (PRD §17).
 * <p>
 * 5분마다 실행: 만료된 HELD 상태의 재고 예약을 해제하고 inventory_histories에 기록합니다.
 */
@Component
@RequiredArgsConstructor
public class InventoryReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationExpiryJob.class);

    private static final String JOB_NAME = "inventoryReservationExpiry";

    private final JobRunTracker jobRunTracker;
    private final InventoryService inventoryService;

    /**
     * 재고 선점 만료 해제 (매 5분).
     */
    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "InventoryReservationExpiryJob", lockAtMostFor = "54m", lockAtLeastFor = "1m")
    public void releaseExpiredReservations() {
        JobRun jobRun = jobRunTracker.start(JOB_NAME, JobRun.TriggeredBy.SCHEDULED);
        int processedCount = 0;
        String errorMessage = null;

        try {
            // InventoryService의 기존 메서드 활용
            processedCount = inventoryService.releaseExpired();
            jobRunTracker.complete(jobRun, processedCount);

        } catch (Exception e) {
            errorMessage = e.getMessage();
            jobRunTracker.fail(jobRun, errorMessage, processedCount);
            log.error("[{}] Job execution failed: {}", JOB_NAME, e.getMessage(), e);
        }
    }

    /**
     * 재고 선점 만료 실제 처리 로직.
     *
     * @return 해제된 예약 수
     */
    public int releaseExpiredInventoryReservations() {
        return inventoryService.releaseExpired();
    }
}
