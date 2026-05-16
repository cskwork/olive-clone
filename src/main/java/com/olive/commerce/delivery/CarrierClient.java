package com.olive.commerce.delivery;

import com.olive.commerce.delivery.Delivery.DeliveryStatus;

/**
 * 택배사 클라이언트 인터페이스.
 * <p>
 * 실제 환경에서는 각 택배사(CJ대한통운, 한진, 로젠 등)의 API를 구현합니다.
 */
public interface CarrierClient {

    /**
     * 운송장 번호로 배송 상태를 조회합니다.
     *
     * @param carrierName 택배사명
     * @param invoiceNo   운송장 번호
     * @return 배송 상태 (조회 실패 시 null)
     */
    DeliveryStatus fetchStatus(String carrierName, String invoiceNo);
}
