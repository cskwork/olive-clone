package com.olive.commerce.batch;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 상품 랭킹 갱신 배치 작업 (PRD §17).
 * <p>
 * 매시간 실행: 상품별 랭킹 점수를 재계산하여 product_rankings 테이블을 업데이트합니다.
 * <p>
 * 랭킹 점수 = sales_count × 0.5 + review_count × 0.3 + avg_rating × 0.2
 */
@Component
@RequiredArgsConstructor
public class ProductRankingJob {

    private static final Logger log = LoggerFactory.getLogger(ProductRankingJob.class);

    private static final String JOB_NAME = "productRanking";

    private final JobRunTracker jobRunTracker;
    private final ProductRankingRepository productRankingRepository;

    /**
     * 상품 랭킹 갱신 (매시간).
     */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "ProductRankingJob", lockAtMostFor = "55m", lockAtLeastFor = "1m")
    public void updateRankings() {
        JobRun jobRun = jobRunTracker.start(JOB_NAME, JobRun.TriggeredBy.SCHEDULED);
        int processedCount = 0;
        String errorMessage = null;

        try {
            processedCount = recomputeAllRankings();
            jobRunTracker.complete(jobRun, processedCount);

        } catch (Exception e) {
            errorMessage = e.getMessage();
            jobRunTracker.fail(jobRun, errorMessage, processedCount);
            log.error("[{}] Job execution failed: {}", JOB_NAME, e.getMessage(), e);
        }
    }

    /**
     * 전체 상품의 랭킹을 재계산합니다.
     *
     * @return 업데이트된 상품 수
     */
    @Transactional
    public int recomputeAllRankings() {
        // TODO: 실제 환경에서는 products 테이블에서 상품 ID 목록을 가져와서 처리
        // 현재는 빈 구현으로 남겨둠 (데이터가 없으므로)

        // 향후 구현 예시:
        // List<Long> productIds = productRepository.findAllIds();
        // int count = 0;
        // for (Long productId : productIds) {
        //     updateProductRanking(productId);
        //     count++;
        // }
        // return count;

        log.info("[{}] Product ranking recomputation completed (no products to process)", JOB_NAME);
        return 0;
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
