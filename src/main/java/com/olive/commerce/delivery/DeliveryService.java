package com.olive.commerce.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.delivery.client.CarrierClient;
import com.olive.commerce.delivery.client.CarrierClientException;
import com.olive.commerce.delivery.client.dto.InvoiceResponse;
import com.olive.commerce.delivery.client.dto.IssueInvoiceRequest;
import com.olive.commerce.delivery.client.dto.ShippingStatusResponse;
import com.olive.commerce.order.Order;
import com.olive.commerce.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 배송 서비스 (PRD §6.9, §15.2).
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private static final int MAX_RETRY_COUNT = 5;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryStatusHistoryRepository historyRepository;
    private final DeliveryRetryQueueRepository retryQueueRepository;
    private final OrderRepository orderRepository;
    private final CarrierClient carrierClient;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           DeliveryStatusHistoryRepository historyRepository,
                           DeliveryRetryQueueRepository retryQueueRepository,
                           OrderRepository orderRepository,
                           CarrierClient carrierClient,
                           ApplicationEventPublisher eventPublisher,
                           ObjectMapper objectMapper) {
        this.deliveryRepository = deliveryRepository;
        this.historyRepository = historyRepository;
        this.retryQueueRepository = retryQueueRepository;
        this.orderRepository = orderRepository;
        this.carrierClient = carrierClient;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * 주문에 대한 배송을 준비합니다.
     * PaymentApprovedEvent 리스너에서 호출됩니다.
     * <p>
     * Note: @Async 리스너에서 호출되므로 항상 새로운 트랜잭션을 시작합니다.
     *
     * @param orderId 주문 ID
     * @return 생성된 배송 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long prepareForOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        Delivery delivery = Delivery.create(orderId, order.getDeliveryAddressId());
        delivery.setCarrierName("MOCK");
        deliveryRepository.saveAndFlush(delivery);

        // 초기 상태 이력 기록
        DeliveryStatusHistory initialHistory = DeliveryStatusHistory.initial(delivery);
        historyRepository.save(initialHistory);

        log.info("Delivery prepared for order: {}, deliveryId: {}", orderId, delivery.getId());

        // 비동기로 운송장 발급 시도
        return delivery.getId();
    }

    /**
     * 운송장 발급을 요청합니다.
     *
     * @param deliveryId 배송 ID
     */
    @Transactional
    public void issueInvoice(Long deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new IllegalArgumentException("Delivery not found: " + deliveryId));

        if (delivery.getStatus() != Delivery.DeliveryStatus.READY) {
            log.warn("Delivery not in READY status: {}, current: {}", deliveryId, delivery.getStatus());
            return;
        }

        try {
            IssueInvoiceRequest request = new IssueInvoiceRequest(
                deliveryId,
                delivery.getCarrierName(),
                delivery.getOrderId(),
                delivery.getDeliveryAddressId()
            );

            InvoiceResponse response = carrierClient.issueInvoice(request);

            if (response.success()) {
                delivery.assignInvoice(response.invoiceNo());
                saveStatusHistory(delivery, null, Delivery.DeliveryStatus.INVOICE.name(), "운송장 발급");
                log.info("Invoice issued for delivery: {}, invoiceNo: {}", deliveryId, response.invoiceNo());
            } else {
                enqueueRetry(delivery, DeliveryRetryQueue.RequestKind.ISSUE_INVOICE, "Invoice failed");
            }

        } catch (CarrierClientException e) {
            log.error("Carrier API error during issueInvoice: {}", deliveryId, e);
            if (e.isRetryable()) {
                enqueueRetry(delivery, DeliveryRetryQueue.RequestKind.ISSUE_INVOICE, e.getMessage());
            }
        }
    }

    /**
     * 배송 상태를 조회하고 업데이트합니다.
     *
     * @param deliveryId 배송 ID
     */
    @Transactional
    public void fetchAndUpdateStatus(Long deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new IllegalArgumentException("Delivery not found: " + deliveryId));

        String invoiceNo = delivery.getInvoiceNo();
        if (invoiceNo == null) {
            log.warn("Invoice not assigned for delivery: {}", deliveryId);
            return;
        }

        try {
            ShippingStatusResponse response = carrierClient.fetchStatus(invoiceNo);

            if (!response.success()) {
                log.warn("Status fetch failed for invoice: {}", invoiceNo);
                return;
            }

            String statusStr = response.status();
            if (statusStr == null) {
                log.warn("Status is null for invoice: {}", invoiceNo);
                return;
            }

            ShippingStatusResponse.CarrierStatus carrierStatus;
            try {
                carrierStatus = ShippingStatusResponse.CarrierStatus.valueOf(statusStr);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown carrier status: {}", statusStr);
                return;
            }

            // 택배사 상태를 우리 상태로 매핑
            Delivery.DeliveryStatus newStatus = mapCarrierStatus(carrierStatus);
            Delivery.DeliveryStatus currentStatus = delivery.getStatus();

            if (newStatus != currentStatus && isValidTransition(currentStatus, newStatus)) {
                String fromStatus = currentStatus.name();
                switch (newStatus) {
                    case SHIPPING -> {
                        delivery.toShipping();
                        saveStatusHistory(delivery, fromStatus, Delivery.DeliveryStatus.SHIPPING.name(), "배송 시작");
                        log.info("Delivery status updated to SHIPPING: {}", deliveryId);
                    }
                    case DELIVERED -> {
                        delivery.toDelivered();
                        saveStatusHistory(delivery, fromStatus, Delivery.DeliveryStatus.DELIVERED.name(), "배송 완료");
                        log.info("Delivery status updated to DELIVERED: {}", deliveryId);

                        // 주문 조회 후 이벤트 발행
                        publishCompletedEvent(delivery);
                    }
                    case RETURNING -> {
                        delivery.toReturning();
                        saveStatusHistory(delivery, fromStatus, Delivery.DeliveryStatus.RETURNING.name(), "반품 시작");
                        log.info("Delivery status updated to RETURNING: {}", deliveryId);
                    }
                    case RETURNED -> {
                        delivery.toReturned();
                        saveStatusHistory(delivery, fromStatus, Delivery.DeliveryStatus.RETURNED.name(), "반품 완료");
                        log.info("Delivery status updated to RETURNED: {}", deliveryId);
                    }
                    default -> log.debug("Status not changed: {}", newStatus);
                }
            }

        } catch (CarrierClientException e) {
            log.error("Carrier API error during fetchStatus: {}", deliveryId, e);
            if (e.isRetryable()) {
                enqueueRetry(delivery, DeliveryRetryQueue.RequestKind.FETCH_STATUS, e.getMessage());
            }
        }
    }

    /**
     * 재시도 큐에 등록합니다.
     */
    private void enqueueRetry(Delivery delivery, DeliveryRetryQueue.RequestKind kind, String error) {
        // 이미 PENDING 상태의 항목이 있는지 확인
        retryQueueRepository
            .findByDeliveryIdAndRequestKindAndStatus(
                delivery.getId(),
                kind,
                DeliveryRetryQueue.QueueStatus.PENDING
            )
            .ifPresentOrElse(
                existing -> log.debug("Retry queue entry already exists: deliveryId={}, kind={}",
                    delivery.getId(), kind),
                () -> {
                    try {
                        String payload = objectMapper.writeValueAsString(
                            Map.of("deliveryId", delivery.getId(), "kind", kind)
                        );
                        // 지수 백오프: 1분, 2분, 4분, 8분, 16분
                        int delayMinutes = (int) Math.pow(2, delivery.getId() % 5);
                        OffsetDateTime nextRetryAt = OffsetDateTime.now().plusMinutes(delayMinutes);

                        DeliveryRetryQueue retry = DeliveryRetryQueue.create(
                            delivery, kind, payload, nextRetryAt
                        );
                        retryQueueRepository.save(retry);
                        log.info("Enqueued retry: deliveryId={}, kind={}, nextRetryAt={}",
                            delivery.getId(), kind, nextRetryAt);
                    } catch (Exception e) {
                        log.error("Failed to enqueue retry", e);
                    }
                }
            );
    }

    /**
     * 상태 전이 유효성 검사 (재시도 큐용).
     */
    private boolean isValidTransition(Delivery.DeliveryStatus from, Delivery.DeliveryStatus to) {
        return switch (from) {
            case READY -> to == Delivery.DeliveryStatus.INVOICE;
            case INVOICE -> to == Delivery.DeliveryStatus.SHIPPING;
            case SHIPPING -> to == Delivery.DeliveryStatus.DELIVERED || to == Delivery.DeliveryStatus.RETURNING;
            case DELIVERED -> false;
            case RETURNING -> to == Delivery.DeliveryStatus.RETURNED;
            case RETURNED -> false;
        };
    }

    /**
     * 택배사 상태를 배송 상태로 매핑합니다.
     */
    private Delivery.DeliveryStatus mapCarrierStatus(ShippingStatusResponse.CarrierStatus carrierStatus) {
        return switch (carrierStatus) {
            case PICKUP, IN_TRANSIT -> Delivery.DeliveryStatus.SHIPPING;
            case DELIVERED -> Delivery.DeliveryStatus.DELIVERED;
            case RETURNING -> Delivery.DeliveryStatus.RETURNING;
            case RETURNED -> Delivery.DeliveryStatus.RETURNED;
            default -> Delivery.DeliveryStatus.READY;
        };
    }

    /**
     * 상태 변경 이력을 저장합니다.
     */
    private void saveStatusHistory(Delivery delivery, String fromStatus, String toStatus, String reason) {
        DeliveryStatusHistory history = DeliveryStatusHistory.transition(
            delivery, fromStatus, toStatus, reason
        );
        historyRepository.save(history);
    }

    /**
     * 배송 완료 이벤트를 발행합니다.
     */
    private void publishCompletedEvent(Delivery delivery) {
        Order order = orderRepository.findById(delivery.getOrderId())
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + delivery.getOrderId()));

        DeliveryCompletedEvent event = new DeliveryCompletedEvent(
            this,
            delivery.getId(),
            delivery.getOrderId(),
            order.getOrderNo(),
            order.getMemberId(),
            delivery.getInvoiceNo()
        );
        eventPublisher.publishEvent(event);
        log.info("DeliveryCompletedEvent published: deliveryId={}, orderId={}",
            delivery.getId(), delivery.getOrderId());
    }
}
