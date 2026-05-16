package com.olive.commerce.search;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Outbox row 접근. 드레이너 워커는 PESSIMISTIC_WRITE + SKIP_LOCKED 힌트로
 * 여러 인스턴스가 같은 row를 동시에 픽업하지 않도록 한다.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * PENDING + dlq=false인 가장 오래된 N건을 락 후 반환.
     * Hibernate {@code jakarta.persistence.lock.timeout = -2} (== SKIP_LOCKED)로
     * 이미 락된 row는 건너뛴다 — 멀티 인스턴스 안전.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    })
    @Query("""
        SELECT o FROM OutboxEvent o
        WHERE o.status = 'PENDING'
          AND o.dlq = false
        ORDER BY o.id ASC
        """)
    List<OutboxEvent> findPendingBatch(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    })
    @Query("""
        SELECT o FROM OutboxEvent o
        WHERE o.status = 'PENDING'
          AND o.dlq = false
          AND o.eventType = :eventType
        ORDER BY o.id ASC
        """)
    List<OutboxEvent> findPendingBatchByEventType(@Param("eventType") String eventType, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    })
    @Query("""
        SELECT o FROM OutboxEvent o
        WHERE o.status = 'PENDING'
          AND o.dlq = false
          AND o.eventType IN :eventTypes
        ORDER BY o.id ASC
        """)
    List<OutboxEvent> findPendingBatchByEventTypeIn(@Param("eventTypes") List<String> eventTypes, Pageable pageable);

    /**
     * 특정 event_type의 PENDING/DLQ 카운트(어드민 모니터링).
     */
    long countByEventTypeAndDlq(String eventType, boolean dlq);

    /**
     * 특정 aggregate의 가장 최근 outbox row (테스트 / 디버그용).
     */
    @Query("""
        SELECT o FROM OutboxEvent o
        WHERE o.aggregateType = :aggregateType
          AND o.aggregateId = :aggregateId
        ORDER BY o.id DESC
        """)
    List<OutboxEvent> findByAggregate(
        @Param("aggregateType") String aggregateType,
        @Param("aggregateId") Long aggregateId,
        Pageable pageable
    );
}
