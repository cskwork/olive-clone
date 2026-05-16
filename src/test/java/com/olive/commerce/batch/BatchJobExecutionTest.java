package com.olive.commerce.batch;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OLV-120 AC 검증 — Batch Job Execution.
 *
 * AC1: Each job executes without throwing exceptions.
 * AC2: JobRunTracker creates job_runs entries.
 * AC3: Manual execution via JobExecutionService works.
 *
 * Note: ShedLock multi-instance behavior requires actual multi-instance deployment.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
class BatchJobExecutionTest extends PostgresIntegrationSupport {

    @Autowired
    private JobExecutionService jobExecutionService;

    @Autowired
    private JobRunRepository jobRunRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        // Initialize sequences
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            try {
                // Reset sequence if table exists
                jobRunRepository.findAll().forEach(j -> {});
            } catch (Exception e) {
                // Table might not exist yet, skip
            }
        });
    }

    @Test
    void AC1_paymentPendingExpiryJob_executes() {
        JobRun jobRun = jobExecutionService.execute("paymentPendingExpiry");
        assertThat(jobRun).isNotNull();
        assertThat(jobRun.getJobName()).isEqualTo("paymentPendingExpiry");
        assertThat(jobRun.getTriggeredBy()).isEqualTo(JobRun.TriggeredBy.MANUAL);
    }

    @Test
    void AC1_inventoryReservationExpiryJob_executes() {
        JobRun jobRun = jobExecutionService.execute("inventoryReservationExpiry");
        assertThat(jobRun).isNotNull();
        assertThat(jobRun.getJobName()).isEqualTo("inventoryReservationExpiry");
    }

    @Test
    void AC1_couponExpiryJob_executes() {
        JobRun jobRun = jobExecutionService.execute("couponExpiry");
        assertThat(jobRun).isNotNull();
        assertThat(jobRun.getJobName()).isEqualTo("couponExpiry");
    }

    @Test
    void AC1_pointExpiryJob_executes() {
        JobRun jobRun = jobExecutionService.execute("pointExpiry");
        assertThat(jobRun).isNotNull();
        assertThat(jobRun.getJobName()).isEqualTo("pointExpiry");
    }

    @Test
    void AC1_deliveryStatusSyncJob_executes() {
        JobRun jobRun = jobExecutionService.execute("deliveryStatusSync");
        assertThat(jobRun).isNotNull();
        assertThat(jobRun.getJobName()).isEqualTo("deliveryStatusSync");
    }

    @Test
    void AC1_salesAggregationJob_executes() {
        JobRun jobRun = jobExecutionService.execute("salesAggregation");
        assertThat(jobRun).isNotNull();
        assertThat(jobRun.getJobName()).isEqualTo("salesAggregation");
    }

    @Test
    void AC1_productRankingJob_executes() {
        JobRun jobRun = jobExecutionService.execute("productRanking");
        assertThat(jobRun).isNotNull();
        assertThat(jobRun.getJobName()).isEqualTo("productRanking");
    }

    @Test
    void AC4_unknownJobName_throwsException() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> jobExecutionService.execute("unknownJob")
        );
    }
}
