package com.olive.commerce.delivery;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 배송 관련 스케줄된 작업 (OLV-080).
 * <p>
 * 1. Mock 배송 상태 전이 워커 (1분 간격)
 * 2. 재시도 큐 처리 워커 (1분 간격)
 * <p>
 * ShedLock으로 멀티 인스턴스 배포 환경에서 중복 실행 방지.
 */
@Component
public class DeliveryScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(DeliveryScheduledTasks.class);

    private final DeliveryRepository deliveryRepository;
    private final DeliveryRetryQueueRepository retryQueueRepository;
    private final DeliveryService deliveryService;

    public DeliveryScheduledTasks(DeliveryRepository deliveryRepository,
                                  DeliveryRetryQueueRepository retryQueueRepository,
                                  DeliveryService deliveryService) {
        this.deliveryRepository = deliveryRepository;
        this.retryQueueRepository = retryQueueRepository;
        this.deliveryService = deliveryService;
    }

    /**
     * Mock 배송 상태 전이 워커.
     * 1분 간격으로 INVOICE/SHIPPING 상태의 배송을 조회하여 상태를 업데이트합니다.
     */
    @Scheduled(fixedDelay = 60000) // 1분
    @SchedulerLock(name = "DeliveryScheduledTasks_walkDeliveryStatuses",
                   lockAtLeastFor = "30s",
                   lockAtMostFor = "55m")
    public void walkDeliveryStatuses() {
        try {
            // INVOICE 상태인 배송 조회 → SHIPPING으로 전이
            List<Delivery> invoiceDeliveries = deliveryRepository.findByStatus(
                Delivery.DeliveryStatus.INVOICE
            );
            for (Delivery delivery : invoiceDeliveries) {
                try {
                    deliveryService.fetchAndUpdateStatus(delivery.getId());
                } catch (Exception e) {
                    log.error("Failed to update delivery status: {}", delivery.getId(), e);
                }
            }

            // SHIPPING 상태인 배송 조회 → DELIVERED로 전이
            List<Delivery> shippingDeliveries = deliveryRepository.findByStatus(
                Delivery.DeliveryStatus.SHIPPING
            );
            for (Delivery delivery : shippingDeliveries) {
                try {
                    deliveryService.fetchAndUpdateStatus(delivery.getId());
                } catch (Exception e) {
                    log.error("Failed to update delivery status: {}", delivery.getId(), e);
                }
            }

        } catch (Exception e) {
            log.error("Error in walkDeliveryStatuses", e);
        }
    }

    /**
     * 재시도 큐 처리 워커.
     * 1분 간격으로 PENDING 상태이고 재시도 시간이 도래한 항목을 처리합니다.
     */
    @Scheduled(fixedDelay = 60000) // 1분
    @SchedulerLock(name = "DeliveryScheduledTasks_processRetryQueue",
                   lockAtLeastFor = "30s",
                   lockAtMostFor = "55m")
    public void processRetryQueue() {
        try {
            List<DeliveryRetryQueue> pendingItems = retryQueueRepository.findPendingForRetry(
                OffsetDateTime.now()
            );

            for (DeliveryRetryQueue item : pendingItems) {
                try {
                    switch (item.getRequestKind()) {
                        case ISSUE_INVOICE -> deliveryService.issueInvoice(item.getDelivery().getId());
                        case FETCH_STATUS -> deliveryService.fetchAndUpdateStatus(item.getDelivery().getId());
                    }

                    // 성공 시 DONE 처리
                    item.markDone();
                    retryQueueRepository.save(item);
                    log.info("Retry queue item processed: {}", item.getId());

                } catch (Exception e) {
                    boolean isDead = item.incrementRetry(
                        OffsetDateTime.now().plusMinutes(5), // 5분 후 재시도
                        e.getMessage()
                    );

                    if (isDead) {
                        item.markDead();
                        log.error("Retry queue item marked as DEAD: {}, retries: {}",
                            item.getId(), item.getRetryCount());
                    }

                    retryQueueRepository.save(item);
                }
            }

        } catch (Exception e) {
            log.error("Error in processRetryQueue", e);
        }
    }
}
