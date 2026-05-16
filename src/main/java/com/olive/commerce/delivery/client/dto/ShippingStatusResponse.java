package com.olive.commerce.delivery.client.dto;

/**
 * 배송 상태 조회 응답 DTO.
 */
public record ShippingStatusResponse(
        String invoiceNo,
        String status,
        boolean success
) {
    /**
     * 배송 상태 (택배사 응답 기준).
     */
    public enum CarrierStatus {
        READY,       // 상품 준비중
        PICKUP,      // 상품 픽업
        IN_TRANSIT,  // 배송중
        DELIVERED,   // 배송 완료
        RETURNING,   // 반품중
        RETURNED     // 반품 완료
    }

    public static ShippingStatusResponse success(String invoiceNo, CarrierStatus status) {
        return new ShippingStatusResponse(invoiceNo, status.name(), true);
    }

    public static ShippingStatusResponse failure(String invoiceNo) {
        return new ShippingStatusResponse(invoiceNo, null, false);
    }
}
