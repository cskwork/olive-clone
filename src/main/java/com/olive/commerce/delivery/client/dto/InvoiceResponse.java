package com.olive.commerce.delivery.client.dto;

/**
 * 운송장 발급 응답 DTO.
 */
public record InvoiceResponse(
        String invoiceNo,
        boolean success
) {
    public static InvoiceResponse success(String invoiceNo) {
        return new InvoiceResponse(invoiceNo, true);
    }

    public static InvoiceResponse failure() {
        return new InvoiceResponse(null, false);
    }
}
