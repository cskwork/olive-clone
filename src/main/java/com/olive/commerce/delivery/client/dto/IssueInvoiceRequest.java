package com.olive.commerce.delivery.client.dto;

/**
 * 운송장 발급 요청 DTO.
 */
public record IssueInvoiceRequest(
        Long deliveryId,
        String carrierName,
        Long orderId,
        Long deliveryAddressId
) {
}
