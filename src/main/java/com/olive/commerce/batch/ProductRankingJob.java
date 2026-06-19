package com.olive.commerce.batch;

import com.olive.commerce.product.ProductRepository;
import com.olive.commerce.review.ProductReviewSummary;
import com.olive.commerce.review.ProductReviewSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 상품 랭킹 갱신 배치 작업 (PRD §17).
 * <p>
 * 매시간 실행: 상품별 랭킹 점수를 재계산하여 product_rankings 테이블을 업데이트합니다.
 * <p>
 * 랭킹 점수 = sales_count × 0.5 + review_count × 0.3 + avg_rating × 0.2
 * <p>
 * 구현: products.sales_count(V18) + product_review_summaries 배치 조회 후 UPSERT.
 * 멱등(idempotent): 재실행해도 결과 동일.
 */
@Component
@RequiredArgsConstructor
public class ProductRankingJob {

    private static final Logger log = LoggerFactory.getLogger(ProductRankingJob.class);

    private static final String JOB_NAME = "productRanking";

    private final JobRunTracker jobRunTracker;
    private final ProductRankingRepository productRankingRepository;
    private final ProductRepository productRepository;
    private final ProductReviewSummaryRepository reviewSummaryRepository;

    /**
     * 상품 랭킹 갱신 (매시간).
     */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "ProductRankingJob", lockAtMostFor = "55m", lockAtLeastFor = "1m")
    public void updateRankings() {
        JobRun jobRun = jobRunTracker.start(JOB_NAME, JobRun.TriggeredBy.SCHEDULED);
        int processedCount = 0;

        try {
            processedCount = recomputeAllRankings();
            jobRunTracker.complete(jobRun, processedCount);

        } catch (Exception e) {
            jobRunTracker.fail(jobRun, e.getMessage(), processedCount);
            log.error("[{}] Job execution failed: {}", JOB_NAME, e.getMessage(), e);
        }
    }

    /**
     * 전체 상품의 랭킹을 재계산합니다.
     *
     * 알고리즘:
     * 1. products 테이블에서 전체 ID + sales_count 조회
     * 2. product_review_summaries 배치 조회 (한 번의 IN 쿼리)
     * 3. 각 상품별 rank_score 계산 후 UPSERT
     *
     * @return 업데이트된 상품 수
     */
    @Transactional
    public int recomputeAllRankings() {
        List<Long> productIds = productRepository.findAllIds();
        if (productIds.isEmpty()) {
            log.info("[{}] No products found; skipping ranking computation", JOB_NAME);
            return 0;
        }

        // Batch-fetch all products (need sales_count)
        List<com.olive.commerce.product.Product> products = productRepository.findAllById(productIds);

        // Batch-fetch review summaries
        Map<Long, ProductReviewSummary> summaryByProductId = reviewSummaryRepository
            .findByProductIdIn(productIds).stream()
            .collect(Collectors.toMap(ProductReviewSummary::getProductId, s -> s));

        // Batch-fetch existing rankings for UPSERT
        Map<Long, ProductRanking> existingByProductId = productRankingRepository
            .findAll().stream()
            .collect(Collectors.toMap(ProductRanking::getProductId, r -> r));

        int count = 0;
        for (com.olive.commerce.product.Product product : products) {
            Long productId = product.getId();
            long salesCount = product.getSalesCount();

            ProductReviewSummary summary = summaryByProductId.get(productId);
            int reviewCount = summary != null ? summary.getReviewCount() : 0;
            BigDecimal avgRating = summary != null ? summary.getAvgRating() : BigDecimal.ZERO;

            ProductRanking ranking = existingByProductId.getOrDefault(
                productId, ProductRanking.create(productId));
            ranking.update((int) Math.min(salesCount, Integer.MAX_VALUE), reviewCount, avgRating);
            productRankingRepository.save(ranking);
            count++;
        }

        log.info("[{}] Ranking recomputation completed: {} products updated", JOB_NAME, count);
        return count;
    }

    /**
     * 특정 상품의 랭킹을 업데이트합니다.
     *
     * @param productId 상품 ID
     */
    @Transactional
    public void updateProductRanking(Long productId) {
        // 판매 수량 집계
        Integer salesCount = productRankingRepository.countSalesByProduct(productId);
        if (salesCount == null) {
            salesCount = 0;
        }

        // 리뷰 통계 집계
        Object[] reviewStats = productRankingRepository.getReviewStatsByProduct(productId);
        int reviewCount = ((Number) reviewStats[0]).intValue();
        BigDecimal avgRating = (BigDecimal) reviewStats[1];

        // 랭킹 엔티티 조회 또는 생성
        ProductRanking ranking = productRankingRepository.findByProductId(productId)
                .orElseGet(() -> ProductRanking.create(productId));

        // 랭킹 데이터 업데이트
        ranking.update(salesCount, reviewCount, avgRating);
        productRankingRepository.save(ranking);

        log.debug("[{}] Product ranking updated: productId={}, sales={}, reviews={}, rating={}, score={}",
                JOB_NAME, productId, salesCount, reviewCount, avgRating, ranking.getRankScore());
    }
}
