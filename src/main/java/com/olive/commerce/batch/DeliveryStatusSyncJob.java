package com.olive.commerce.batch;

import com.olive.commerce.delivery.client.CarrierClient;
import com.olive.commerce.delivery.client.dto.ShippingStatusResponse;
import com.olive.commerce.delivery.Delivery;
import com.olive.commerce.delivery.Delivery.DeliveryStatus;
import com.olive.commerce.delivery.DeliveryRepository;
import com.olive.commerce.delivery.DeliveryRetryQueue;
import com.olive.commerce.delivery.DeliveryRetryQueueRepository;
import com.olive.commerce.delivery.DeliveryStatusHistory;
import com.olive.commerce.delivery.DeliveryStatusHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 배송 상태 동기화 배치 작업 (PRD §17).
 * <p>
 * 10분마다 실행: 활성 배송(INVOICE, SHIPPING)의 상태를 택배사 API에서 조회하고
 * 상태를 업데이트합니다. 실패 시 재시도 큐에 등록합니다.
 */
@Component
@RequiredArgsConstructor
public class DeliveryStatusSyncJob {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStatusSyncJob.class);

    private static final String JOB_NAME = "deliveryStatusSync";
    private static final int RETRY_MINUTES = 10;

    private final JobRunTracker jobRunTracker;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryStatusHistoryRepository statusHistoryRepository;
    private final DeliveryRetryQueueRepository retryQueueRepository;
    private final CarrierClient carrierClient;
    private final ObjectMapper objectMapper;

    /**
     * 배송 상태 동기화 (매 10분).
     */
    @Scheduled(cron = "0 */10 * * * *")
    @SchedulerLock(name = "DeliveryStatusSyncJob", lockAtMostFor = "54m", lockAtLeastFor = "1m")
    public void syncDeliveryStatuses() {
        JobRun jobRun = jobRunTracker.start(JOB_NAME, JobRun.TriggeredBy.SCHEDULED);
        int processedCount = 0;
        String errorMessage = null;

        try {
            processedCount = syncActiveDeliveries();
            jobRunTracker.complete(jobRun, processedCount);

        } catch (Exception e) {
            errorMessage = e.getMessage();
            jobRunTracker.fail(jobRun, errorMessage, processedCount);
            log.error("[{}] Job execution failed: {}", JOB_NAME, e.getMessage(), e);
        }
    }

    /**
     * 활성 배송의 상태를 동기화합니다.
     *
     * @return 처리된 배송 수
     */
    @Transactional
    public int syncActiveDeliveries() {
        // INVOICE 또는 SHIPPING 상태의 배송 조회
        List<Delivery> activeDeliveries = deliveryRepository.findByStatus(DeliveryStatus.INVOICE);
        activeDeliveries.addAll(deliveryRepository.findByStatus(DeliveryStatus.SHIPPING));

        int count = 0;
        for (Delivery delivery : activeDeliveries) {
            if (syncDelivery(delivery)) {
                count++;
            }
        }

        // 재시도 큐 처리
        count += processRetryQueue();

        return count;
    }

    /**
     * 개별 배송 상태 동기화.
     *
     * @return 상태가 변경되면 true
     */
    private boolean syncDelivery(Delivery delivery) {
        String invoiceNo = delivery.getInvoiceNo();
        if (invoiceNo == null || invoiceNo.isBlank()) {
            return false; // 운송장 없음
        }

        try {
            ShippingStatusResponse response = carrierClient.fetchStatus(invoiceNo);

            if (!response.success()) {
                // 택배사 조회 실패 - 재시도 큐에 등록
                enqueueRetry(delivery, "Carrier API returned failure");
                return false;
            }

            DeliveryStatus carrierStatus = toDeliveryStatus(ShippingStatusResponse.CarrierStatus.valueOf(response.status()));

            if (carrierStatus == delivery.getStatus()) {
                return false; // 상태 변화 없음
            }

            // 상태 전이
            DeliveryStatus fromStatus = delivery.getStatus();
            transitionDeliveryStatus(delivery, carrierStatus);
            log.info("Delivery status updated: deliveryId={}, invoiceNo={}, {} -> {}",
                    delivery.getId(), invoiceNo, fromStatus, carrierStatus);
            return true;

        } catch (Exception e) {
            log.warn("Failed to sync delivery status: deliveryId={}, error={}",
                    delivery.getId(), e.getMessage());
            enqueueRetry(delivery, e.getMessage());
            return false;
        }
    }

    /**
     * ShippingStatusResponse.CarrierStatus를 DeliveryStatus로 변환합니다.
     */
    private DeliveryStatus toDeliveryStatus(ShippingStatusResponse.CarrierStatus carrierStatus) {
        if (carrierStatus == null) {
            return DeliveryStatus.SHIPPING; // 기본값
        }
        return switch (carrierStatus) {
            case PICKUP, READY -> DeliveryStatus.INVOICE;
            case IN_TRANSIT -> DeliveryStatus.SHIPPING;
            case DELIVERED -> DeliveryStatus.DELIVERED;
            case RETURNING, RETURNED -> DeliveryStatus.RETURNED;
            default -> {
                log.warn("Unknown carrier status: {}", carrierStatus);
                yield DeliveryStatus.SHIPPING; // 기본값
            }
        };
    }

    /**
     * 배송 상태를 전이하고 이력을 기록합니다.
     */
    private void transitionDeliveryStatus(Delivery delivery, DeliveryStatus newStatus) {
        DeliveryStatus fromStatus = delivery.getStatus();

        switch (newStatus) {
            case SHIPPING -> delivery.toShipping();
            case DELIVERED -> delivery.toDelivered();
            case RETURNING -> delivery.toReturning();
            case RETURNED -> delivery.toReturned();
            default -> {
                log.warn("Unexpected status transition for delivery {}: {}", delivery.getId(), newStatus);
                return;
            }
        }

        deliveryRepository.save(delivery);

        // 상태 이력 기록
        DeliveryStatusHistory history = DeliveryStatusHistory.transition(
                delivery,
                fromStatus.name(),
                newStatus.name(),
                "택배사 API 동기화"
        );
        statusHistoryRepository.save(history);
    }

    /**
     * 재시도 큐에 등록합니다.
     */
    private void enqueueRetry(Delivery delivery, String errorMessage) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("deliveryId", delivery.getId());
            payload.put("invoiceNo", delivery.getInvoiceNo());
            payload.put("carrierName", delivery.getCarrierName());

            DeliveryRetryQueue retryQueue = DeliveryRetryQueue.create(
                    delivery,
                    DeliveryRetryQueue.RequestKind.FETCH_STATUS,
                    objectMapper.writeValueAsString(payload),
                    OffsetDateTime.now().plusMinutes(RETRY_MINUTES)
            );
            retryQueueRepository.save(retryQueue);

        } catch (Exception e) {
            log.error("Failed to enqueue retry for delivery {}: {}", delivery.getId(), e.getMessage());
        }
    }

    /**
     * 재시도 큐를 처리합니다.
     *
     * @return 성공적으로 처리된 항목 수
     */
    @Transactional
    public int processRetryQueue() {
        List<DeliveryRetryQueue> pendingRetries = retryQueueRepository.findPendingForRetry(OffsetDateTime.now());
        int count = 0;

        for (DeliveryRetryQueue retry : pendingRetries) {

            Delivery delivery = retry.getDelivery();
            if (delivery == null) {
                retry.markDead();
                retryQueueRepository.save(retry);
                continue;
            }

            try {
                boolean synced = syncDelivery(delivery);
                if (synced) {
                    retry.markDone();
                    retryQueueRepository.save(retry);
                    count++;
                } else {
                    OffsetDateTime nextRetry = OffsetDateTime.now().plusMinutes(RETRY_MINUTES);
                    boolean isDead = retry.incrementRetry(nextRetry, "Sync failed");
                    retryQueueRepository.save(retry);
                    if (isDead) {
                        retry.markDead();
                        retryQueueRepository.save(retry);
                        log.warn("Retry queue entry marked as DEAD: id={}, deliveryId={}",
                                retry.getId(), delivery.getId());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to process retry queue entry {}: {}", retry.getId(), e.getMessage());
            }
        }

        return count;
    }
}
