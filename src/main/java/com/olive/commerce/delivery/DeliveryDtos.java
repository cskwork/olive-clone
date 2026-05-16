package com.olive.commerce.delivery;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 배송 관련 DTO.
 */
public class DeliveryDtos {

    /**
     * 배송 목록 응답.
     */
    public record DeliveryListResponse(
            List<DeliveryDto> deliveries
    ) {
        public static DeliveryListResponse of(List<Delivery> deliveries) {
            return new DeliveryListResponse(
                deliveries.stream()
                    .map(DeliveryDto::from)
                    .toList()
            );
        }
    }

    /**
     * 배송 DTO.
     */
    public record DeliveryDto(
            Long id,
            Long orderId,
            String carrierName,
            String invoiceNo,
            String status,
            String statusDescription,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static DeliveryDto from(Delivery delivery) {
            return new DeliveryDto(
                delivery.getId(),
                delivery.getOrderId(),
                delivery.getCarrierName(),
                delivery.getInvoiceNo(),
                delivery.getStatus().name(),
                getStatusDescription(delivery.getStatus()),
                delivery.getCreatedAt(),
                delivery.getUpdatedAt()
            );
        }

        private static String getStatusDescription(Delivery.DeliveryStatus status) {
            return switch (status) {
                case READY -> "배송 준비";
                case INVOICE -> "운송장 등록";
                case SHIPPING -> "배송중";
                case DELIVERED -> "배송 완료";
                case RETURNING -> "반품중";
                case RETURNED -> "반품 완료";
            };
        }
    }

    /**
     * 배송 상세 응답 (이력 포함).
     */
    public record DeliveryDetailResponse(
            Long id,
            Long orderId,
            String carrierName,
            String invoiceNo,
            String status,
            String statusDescription,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            List<StatusHistoryDto> statusHistories
    ) {
        public static DeliveryDetailResponse from(Delivery delivery, List<DeliveryStatusHistory> histories) {
            return new DeliveryDetailResponse(
                delivery.getId(),
                delivery.getOrderId(),
                delivery.getCarrierName(),
                delivery.getInvoiceNo(),
                delivery.getStatus().name(),
                getStatusDescription(delivery.getStatus()),
                delivery.getCreatedAt(),
                delivery.getUpdatedAt(),
                histories.stream()
                    .map(StatusHistoryDto::from)
                    .toList()
            );
        }

        private static String getStatusDescription(Delivery.DeliveryStatus status) {
            return switch (status) {
                case READY -> "배송 준비";
                case INVOICE -> "운송장 등록";
                case SHIPPING -> "배송중";
                case DELIVERED -> "배송 완료";
                case RETURNING -> "반품중";
                case RETURNED -> "반품 완료";
            };
        }
    }

    /**
     * 상태 변경 이력 DTO.
     */
    public record StatusHistoryDto(
            Long id,
            String fromStatus,
            String toStatus,
            String toStatusDescription,
            String reason,
            OffsetDateTime createdAt
    ) {
        public static StatusHistoryDto from(DeliveryStatusHistory history) {
            return new StatusHistoryDto(
                history.getId(),
                history.getFromStatus(),
                history.getToStatus(),
                getStatusDescription(history.getToStatus()),
                history.getReason(),
                history.getCreatedAt()
            );
        }

        private static String getStatusDescription(String status) {
            if (status == null) return "-";
            return switch (status) {
                case "READY" -> "배송 준비";
                case "INVOICE" -> "운송장 등록";
                case "SHIPPING" -> "배송중";
                case "DELIVERED" -> "배송 완료";
                case "RETURNING" -> "반품중";
                case "RETURNED" -> "반품 완료";
                default -> status;
            };
        }
    }

    /**
     * 관리자용 배송 목록 조회 요청.
     */
    public record DeliveryAdminSearchRequest(
            String status,
            Integer page,
            Integer size
    ) {
        public DeliveryAdminSearchRequest {
            if (page == null || page < 0) page = 0;
            if (size == null || size <= 0) size = 20;
            if (size > 100) size = 100; // 최대 100개
        }
    }

    /**
     * 관리자용 배송 목록 응답 (페이지).
     */
    public record DeliveryAdminListResponse(
            List<DeliveryDto> deliveries,
            int page,
            int size,
            long total
    ) {
        public static DeliveryAdminListResponse of(List<Delivery> deliveries, int page, int size, long total) {
            return new DeliveryAdminListResponse(
                deliveries.stream()
                    .map(DeliveryDto::from)
                    .toList(),
                page,
                size,
                total
            );
        }
    }

    /**
     * 운송장 발급 재시도 응답.
     */
    public record InvoiceRetryResponse(
            boolean success,
            String message
    ) {
        public static InvoiceRetryResponse ok() {
            return new InvoiceRetryResponse(true, "운송장 발급이 재시도되었습니다.");
        }

        public static InvoiceRetryResponse fail(String message) {
            return new InvoiceRetryResponse(false, message);
        }
    }
}
