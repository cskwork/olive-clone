package com.olive.commerce.batch;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.function.IntSupplier;

/**
 * 배치 작업 수동 실행 서비스 (OLV-120).
 * <p>관리자 API 요청에 따라 작업을 수동으로 실행하고 실행 기록을 반환합니다.
 */
@Service
@RequiredArgsConstructor
public class JobExecutionService {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionService.class);

    private final JobRunTracker jobRunTracker;
    private final PaymentPendingExpiryJob paymentPendingExpiryJob;
    private final InventoryReservationExpiryJob inventoryReservationExpiryJob;
    private final CouponExpiryJob couponExpiryJob;
    private final PointExpiryJob pointExpiryJob;
    private final DeliveryStatusSyncJob deliveryStatusSyncJob;
    private final SalesAggregationJob salesAggregationJob;
    private final ProductRankingJob productRankingJob;

    /**
     * 작업을 수동으로 실행하고 실행 기록을 반환합니다.
     *
     * @param jobName 작업 이름
     * @return 실행 기록
     * @throws IllegalArgumentException 알 수 없는 작업 이름인 경우
     */
    public JobRun execute(String jobName) {
        switch (jobName) {
            case "paymentPendingExpiry" -> {
                JobRun jobRun = jobRunTracker.start(jobName, JobRun.TriggeredBy.MANUAL);
                executeWithTracking(jobRun, paymentPendingExpiryJob::expirePendingPayments);
                return jobRun;
            }
            case "inventoryReservationExpiry" -> {
                JobRun jobRun = jobRunTracker.start(jobName, JobRun.TriggeredBy.MANUAL);
                executeWithTracking(jobRun, inventoryReservationExpiryJob::releaseExpiredInventoryReservations);
                return jobRun;
            }
            case "couponExpiry" -> {
                JobRun jobRun = jobRunTracker.start(jobName, JobRun.TriggeredBy.MANUAL);
                executeWithTracking(jobRun, couponExpiryJob::expireExpiredCoupons);
                return jobRun;
            }
            case "pointExpiry" -> {
                JobRun jobRun = jobRunTracker.start(jobName, JobRun.TriggeredBy.MANUAL);
                executeWithTracking(jobRun, pointExpiryJob::expireEarnedPoints);
                return jobRun;
            }
            case "deliveryStatusSync" -> {
                JobRun jobRun = jobRunTracker.start(jobName, JobRun.TriggeredBy.MANUAL);
                executeWithTracking(jobRun, deliveryStatusSyncJob::syncActiveDeliveries);
                return jobRun;
            }
            case "salesAggregation" -> {
                JobRun jobRun = jobRunTracker.start(jobName, JobRun.TriggeredBy.MANUAL);
                executeWithTracking(jobRun, () -> salesAggregationJob.aggregateDailySales(LocalDate.now().minusDays(1)));
                return jobRun;
            }
            case "productRanking" -> {
                JobRun jobRun = jobRunTracker.start(jobName, JobRun.TriggeredBy.MANUAL);
                executeWithTracking(jobRun, productRankingJob::recomputeAllRankings);
                return jobRun;
            }
            default -> throw new IllegalArgumentException("Unknown job: " + jobName);
        }
    }

    /**
     * 작업을 실행하고 성공/실패를 추적합니다.
     *
     * @param jobRun   실행 기록
     * @param runnable 작업 로직
     */
    private void executeWithTracking(JobRun jobRun, IntSupplier jobLogic) {
        try {
            int processedCount = jobLogic.getAsInt();
            jobRunTracker.complete(jobRun, processedCount);
        } catch (Exception e) {
            jobRunTracker.fail(jobRun, e.getMessage(), 0);
            throw new JobExecutionException(jobRun.getJobName(), e);
        }
    }

    /**
     * 배치 작업 실행 실패 예외.
     */
    public static class JobExecutionException extends RuntimeException {
        private final String jobName;

        public JobExecutionException(String jobName, Throwable cause) {
            super("Job execution failed: " + jobName, cause);
            this.jobName = jobName;
        }

        public String getJobName() {
            return jobName;
        }
    }
}
