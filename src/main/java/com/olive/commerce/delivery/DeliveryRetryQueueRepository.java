package com.olive.commerce.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 배송 재시도 큐 Repository.
 */
public interface DeliveryRetryQueueRepository extends JpaRepository<DeliveryRetryQueue, Long> {

    /**
     * PENDING 상태이고 재시도 시간이 지난 항목을 조회 (드레이너용).
     */
    @Query("SELECT q FROM DeliveryRetryQueue q WHERE q.status = 'PENDING' AND q.nextRetryAt <= :now ORDER BY q.nextRetryAt")
    List<DeliveryRetryQueue> findPendingForRetry(@Param("now") OffsetDateTime now);

    /**
     * 배송 ID와 요청 종류로 PENDING 상태의 재시도 항목 조회.
     */
    Optional<DeliveryRetryQueue> findByDeliveryIdAndRequestKindAndStatus(
            Long deliveryId,
            DeliveryRetryQueue.RequestKind requestKind,
            DeliveryRetryQueue.QueueStatus status
    );
}
