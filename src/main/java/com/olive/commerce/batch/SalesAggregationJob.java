package com.olive.commerce.batch;

import com.olive.commerce.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 매출 집계 배치 작업 (PRD §17).
 * <p>
 * 매일 02:00 실행: 전날의 매출을 카테고리/브랜드/상품별로 집계하여 daily_sales_summaries에 저장합니다.
 * <p>
 * A5: aggregateDailySales 후 products.sales_count를 전체 PAID/DELIVERED 주문 기준으로 갱신합니다.
 */
@Component
@RequiredArgsConstructor
public class SalesAggregationJob {

    private static final Logger log = LoggerFactory.getLogger(SalesAggregationJob.class);

    private static final String JOB_NAME = "salesAggregation";

    private final JobRunTracker jobRunTracker;
    private final DailySalesSummaryRepository dailySalesSummaryRepository;
    private final ProductRepository productRepository;

    /**
     * 매출 집계 (매일 02:00).
     */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "SalesAggregationJob", lockAtMostFor = "55m", lockAtLeastFor = "1m")
    public void aggregateSales() {
        JobRun jobRun = jobRunTracker.start(JOB_NAME, JobRun.TriggeredBy.SCHEDULED);
        int processedCount = 0;
        String errorMessage = null;

        try {
            // 전날 (00:00 ~ 23:59) 집계
            LocalDate targetDate = LocalDate.now().minusDays(1);
            processedCount = aggregateDailySales(targetDate);

            // products.sales_count 갱신 (POPULAR 정렬 및 랭킹 계산 기반 데이터)
            refreshProductSalesCounts();

            jobRunTracker.complete(jobRun, processedCount);

        } catch (Exception e) {
            errorMessage = e.getMessage();
            jobRunTracker.fail(jobRun, errorMessage, processedCount);
            log.error("[{}] Job execution failed: {}", JOB_NAME, e.getMessage(), e);
        }
    }

    /**
     * 특정 날짜의 매출을 집계합니다.
     *
     * @param targetDate 집계 대상 날짜
     * @return 집계된 항목 수
     */
    @Transactional
    public int aggregateDailySales(LocalDate targetDate) {
        OffsetDateTime startOfDay = targetDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime endOfDay = targetDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        int count = 0;

        // TODO: 실제 상품/브랜드/카테고리 ID 목록을 조회하여 집계
        // 현재는 전체 집계만 수행

        // 전체 일 매출 집계 (categoryId, brandId, productId 모두 null)
        count += upsertTotalSales(targetDate, startOfDay, endOfDay);

        // TODO: 카테고리별, 브랜드별, 상품별 집계는 추후 구현
        // (데이터가 충분히 쌓인 후)

        return count;
    }

    /**
     * 전체 일 매출을 집계하여 UPSERT합니다.
     */
    private int upsertTotalSales(LocalDate targetDate, OffsetDateTime startOfDay, OffsetDateTime endOfDay) {
        Object[] result = singleRow(dailySalesSummaryRepository.computeTotalSalesForDate(startOfDay, endOfDay));
        int orderCount = ((Number) result[0]).intValue();
        BigDecimal totalAmount = toBigDecimal(result[1]);

        // 기존 집계가 있으면 업데이트, 없으면 생성
        DailySalesSummary summary = dailySalesSummaryRepository
                .findBySummaryDateAndCategoryIdAndBrandIdAndProductId(targetDate, null, null, null)
                .orElseGet(() -> DailySalesSummary.create(targetDate, null, null, null));

        if (summary.getId() == null) {
            dailySalesSummaryRepository.save(summary);
        }

        summary.replaceAmounts(orderCount, totalAmount);
        dailySalesSummaryRepository.save(summary);

        log.info("[{}] Total sales aggregated: date={}, orderCount={}, amount={}",
                JOB_NAME, targetDate, orderCount, totalAmount);

        return 1;
    }

    /**
     * 전체 products.sales_count를 PAID/DELIVERED 주문 기준으로 갱신합니다.
     *
     * 멱등(idempotent): 재실행 시 동일 결과. POPULAR 정렬과 ProductRankingJob에서 사용됩니다.
     *
     * @return 갱신된 행 수
     */
    @Transactional
    public int refreshProductSalesCounts() {
        int updated = productRepository.refreshAllSalesCounts();
        log.info("[{}] products.sales_count refreshed: {} rows updated", JOB_NAME, updated);
        return updated;
    }

    /**
     * 상품별 일 매출을 집계합니다.
     *
     * @param targetDate 집계 날짜
     * @param productId 상품 ID
     * @return 처리된 항목 수
     */
    @Transactional
    public int aggregateProductSales(LocalDate targetDate, Long productId) {
        OffsetDateTime startOfDay = targetDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime endOfDay = targetDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        Object[] result = singleRow(dailySalesSummaryRepository.computeProductSalesForDate(
                startOfDay, endOfDay, productId));
        int orderCount = ((Number) result[0]).intValue();
        BigDecimal totalAmount = toBigDecimal(result[1]);

        DailySalesSummary summary = dailySalesSummaryRepository
                .findBySummaryDateAndProductId(targetDate, productId)
                .orElseGet(() -> DailySalesSummary.create(targetDate, null, null, productId));

        summary.replaceAmounts(orderCount, totalAmount);
        dailySalesSummaryRepository.save(summary);

        return 1;
    }

    private static Object[] singleRow(Object[] result) {
        if (result.length == 1 && result[0] instanceof Object[] nested) {
            return nested;
        }
        return result;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(value.toString());
    }
}
