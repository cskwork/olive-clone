package com.olive.commerce.delivery.client;

import com.olive.commerce.delivery.client.dto.InvoiceResponse;
import com.olive.commerce.delivery.client.dto.IssueInvoiceRequest;
import com.olive.commerce.delivery.client.dto.ShippingStatusResponse;

/**
 * 택배사 클라이언트 인터페이스.
 * 실제 택배사 구현과 Mock 구현을 추상화한다.
 */
public interface CarrierClient {

    /**
     * 운송장 발급 요청.
     *
     * @param request 운송장 발급 요청
     * @return 운송장 번호 응답
     * @throws CarrierClientException 택배사 API 호출 실패 시
     */
    InvoiceResponse issueInvoice(IssueInvoiceRequest request);

    /**
     * 배송 상태 조회.
     *
     * @param invoiceNo 운송장 번호
     * @return 배송 상태 응답
     * @throws CarrierClientException 택배사 API 호출 실패 시
     */
    ShippingStatusResponse fetchStatus(String invoiceNo);
}
