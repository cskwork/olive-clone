package com.olive.commerce.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Outbox row 드레이너 (PRD §12, §18.3, wiki §96-eventing).
 *
 * <p>1초 주기로 status='PENDING' AND dlq=false 상위 100건을 SELECT FOR UPDATE
 * SKIP_LOCKED로 픽업 → OpenSearch bulk index → 성공 시 'DONE', 실패 시
 * attempt_count+1 (5회 도달 시 dlq=true). 모든 예외는 잡아서 워커가 죽지 않게
 * 한다 — OpenSearch 다운 시에도 다음 tick에서 재시도(AC3).
 */
@Component
public class OutboxIndexerWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxIndexerWorker.class);

    private final OutboxEventRepository outboxRepository;
    private final ProductIndexer productIndexer;
    private final TransactionTemplate txTemplate;

    public OutboxIndexerWorker(
        OutboxEventRepository outboxRepository,
        ProductIndexer productIndexer,
        PlatformTransactionManager transactionManager
    ) {
        this.outboxRepository = outboxRepository;
        this.productIndexer = productIndexer;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 1초 fixed-delay 폴링. Spring {@code @Scheduled}가 단일 스레드 풀에서 호출하므로
     * 본 메서드 내부에서 추가 동시성 제어는 불필요. 다중 인스턴스 동시 실행도
     * PESSIMISTIC_WRITE + SKIP_LOCKED로 안전.
     */
    @Scheduled(fixedDelay = 1000)
    public void drain() {
        try {
            drainOnce();
        } catch (Exception e) {
            // 워커가 예외로 죽지 않게 — 다음 tick에서 자연스럽게 재시도.
            log.warn("OutboxIndexerWorker drain failed (will retry next tick)", e);
        }
    }

    public int drainOnce() {
        List<OutboxEvent> claimed = claimBatch();
        if (claimed.isEmpty()) {
            return 0;
        }

        List<Long> productIds = new ArrayList<>(claimed.size());
        for (OutboxEvent ev : claimed) {
            if ("PRODUCT_INDEX_SYNC".equals(ev.getEventType())) {
                productIds.add(ev.getAggregateId());
            }
        }

        boolean success = true;
        String errorMessage = null;
        try {
            productIndexer.indexBulk(productIds);
        } catch (IndexingException e) {
            success = false;
            errorMessage = e.getMessage();
            log.warn("Bulk index failed for {} products (will retry per row)", productIds.size(), e);
        }

        finalizeBatch(claimed, success, errorMessage);
        return claimed.size();
    }

    /**
     * 1단계 트랜잭션: PENDING → IN_PROGRESS로 marking + 락 획득.
     */
    private List<OutboxEvent> claimBatch() {
        return txTemplate.execute(status -> {
            List<OutboxEvent> rows = outboxRepository.findPendingBatch(PageRequest.of(0, ProductIndexer.BULK_SIZE));
            for (OutboxEvent row : rows) {
                row.markInProgress();
            }
            // markInProgress가 dirty checking으로 flush — 트랜잭션 종료 시 commit.
            return rows;
        });
    }

    /**
     * 2단계 트랜잭션: bulk 결과를 row-by-row로 반영. bulk 단위 성공/실패 +
     * 개별 row의 dirty checking으로 row마다 attempt_count/dlq를 업데이트.
     */
    private void finalizeBatch(List<OutboxEvent> claimed, boolean success, String errorMessage) {
        txTemplate.executeWithoutResult(status -> {
            // markInProgress 단계에서 분리된 영속성 컨텍스트를 다시 attach.
            List<Long> ids = claimed.stream().map(OutboxEvent::getId).toList();
            List<OutboxEvent> fresh = outboxRepository.findAllById(ids);
            for (OutboxEvent row : fresh) {
                if (success) {
                    row.markDone();
                } else {
                    row.markFailure(errorMessage);
                }
            }
        });
    }
}
