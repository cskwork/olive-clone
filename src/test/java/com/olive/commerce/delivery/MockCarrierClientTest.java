package com.olive.commerce.delivery;

import com.olive.commerce.common.config.CarrierMockProperties;
import com.olive.commerce.delivery.client.MockCarrierClient;
import com.olive.commerce.delivery.client.dto.IssueInvoiceRequest;
import com.olive.commerce.delivery.client.dto.ShippingStatusResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockCarrierClientTest {

    @Test
    void nonImmediateMode_progressesFromPickupToDeliveredAcrossStatusFetches() {
        CarrierMockProperties properties = new CarrierMockProperties();
        properties.setImmediateMode(false);
        MockCarrierClient client = new MockCarrierClient(properties);

        String invoiceNo = client.issueInvoice(
            new IssueInvoiceRequest(1L, "MOCK", 10L, 20L)
        ).invoiceNo();

        ShippingStatusResponse first = client.fetchStatus(invoiceNo);
        ShippingStatusResponse second = client.fetchStatus(invoiceNo);

        assertThat(first.status()).isEqualTo(ShippingStatusResponse.CarrierStatus.IN_TRANSIT.name());
        assertThat(second.status()).isEqualTo(ShippingStatusResponse.CarrierStatus.DELIVERED.name());
    }
}
