package com.olive.commerce.delivery.client;

import com.olive.commerce.common.config.CarrierMockProperties;
import com.olive.commerce.delivery.client.dto.InvoiceResponse;
import com.olive.commerce.delivery.client.dto.IssueInvoiceRequest;
import com.olive.commerce.delivery.client.dto.ShippingStatusResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock 택배사 클라이언트 구현 (OLV-080).
 * <p>
 * QA에서 배송 성공/실패 케이스를 재시작 없이 테스트할 수 있다.
 * <p>
 * 동작 모드는 {@link CarrierMockProperties}로 제어한다.
 */
@Component
@ConditionalOnProperty(name = "carrier.mock.enabled", havingValue = "true", matchIfMissing = true)
public class MockCarrierClient implements CarrierClient {

    private final CarrierMockProperties properties;

    /**
     * 배송 상태 저장소 (테스트용 상태 조회).
     * 운송장 번호 -> 현재 상태
     */
    private final Map<String, String> invoiceStatuses = new ConcurrentHashMap<>();

    /**
     * 테스트용 동작 모드 오버라이드.
     */
    private volatile String behaviourOverride = null;

    /**
     * 테스트용 즉시 모드 오버라이드.
     */
    private volatile Boolean immediateModeOverride = null;

    public MockCarrierClient(CarrierMockProperties properties) {
        this.properties = properties;
    }

    public void setBehaviour(String behaviour) {
        this.behaviourOverride = behaviour;
    }

    public void setImmediateMode(boolean immediateMode) {
        this.immediateModeOverride = immediateMode;
    }

    private String getBehaviour() {
        return behaviourOverride != null ? behaviourOverride : properties.getBehaviour();
    }

    private boolean isImmediateMode() {
        return immediateModeOverride != null ? immediateModeOverride : properties.isImmediateMode();
    }

    /**
     * 운송장 번호로 현재 상태를 조회합니다 (테스트용).
     */
    public String getInvoiceStatus(String invoiceNo) {
        return invoiceStatuses.get(invoiceNo);
    }

    @Override
    public InvoiceResponse issueInvoice(IssueInvoiceRequest request) {
        if ("fail".equals(getBehaviour())) {
            throw new CarrierClientException("Mock carrier invoice failure", true);
        }

        String invoiceNo = "mock-invoice-" + UUID.randomUUID();
        invoiceStatuses.put(invoiceNo, ShippingStatusResponse.CarrierStatus.PICKUP.name());

        if (isImmediateMode()) {
            // 즉시 모드: 바로 배송중 상태로
            invoiceStatuses.put(invoiceNo, ShippingStatusResponse.CarrierStatus.IN_TRANSIT.name());
        }

        return InvoiceResponse.success(invoiceNo);
    }

    @Override
    public ShippingStatusResponse fetchStatus(String invoiceNo) {
        if ("status-fail".equals(getBehaviour())) {
            throw new CarrierClientException("Mock carrier status fetch failure", true);
        }

        if (!invoiceStatuses.containsKey(invoiceNo)) {
            return ShippingStatusResponse.failure(invoiceNo);
        }

        String currentStatus = invoiceStatuses.get(invoiceNo);

        // Mock 상태 전이 로직
        String nextStatus = switch (currentStatus) {
            case "PICKUP" -> ShippingStatusResponse.CarrierStatus.IN_TRANSIT.name();
            case "IN_TRANSIT" -> ShippingStatusResponse.CarrierStatus.DELIVERED.name();
            default -> currentStatus;
        };

        invoiceStatuses.put(invoiceNo, nextStatus);

        return ShippingStatusResponse.success(
            invoiceNo,
            ShippingStatusResponse.CarrierStatus.valueOf(nextStatus)
        );
    }

    /**
     * 테스트용: 운송장 상태를 강제로 설정합니다.
     */
    public void setInvoiceStatus(String invoiceNo, ShippingStatusResponse.CarrierStatus status) {
        invoiceStatuses.put(invoiceNo, status.name());
    }

    /**
     * 테스트용: 모든 상태를 초기화합니다.
     */
    public void clear() {
        invoiceStatuses.clear();
        behaviourOverride = null;
        immediateModeOverride = null;
    }
}
