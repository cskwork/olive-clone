package com.olive.commerce.payment;

import com.olive.commerce.payment.client.MockPgClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 웹훅 서명 단위 테스트 (OLV-073).
 */
class PaymentWebhookTest {

    @Test
    @DisplayName("웹훅 서명 생성 및 검증 성공")
    void webhookSignature_generationAndVerification() {
        // Given
        MockPgClient mockPgClient = new MockPgClient();
        String payload = "{\"paymentKey\":\"test-key\",\"status\":\"APPROVED\"}";

        // When
        String signature = mockPgClient.signWebhook(payload);
        boolean isValid = mockPgClient.verifyWebhookSignature(payload, signature);

        // Then
        assertThat(isValid).isTrue();
        assertThat(signature).isNotEmpty();
        assertThat(signature).isNotEqualTo(payload);
    }

    @Test
    @DisplayName("웹훅 서명 검증 실패 - 잘못된 서명")
    void webhookSignature_verification_fails_withInvalidSignature() {
        // Given
        MockPgClient mockPgClient = new MockPgClient();
        String payload = "{\"paymentKey\":\"test-key\",\"status\":\"APPROVED\"}";
        String wrongSignature = "invalid-signature-base64";

        // When
        boolean isValid = mockPgClient.verifyWebhookSignature(payload, wrongSignature);

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("동일한 payload에 대해 동일한 서명 생성")
    void webhookSignature_samePayload_sameSignature() {
        // Given
        MockPgClient mockPgClient = new MockPgClient();
        String payload = "{\"paymentKey\":\"test-key\",\"status\":\"APPROVED\"}";

        // When
        String signature1 = mockPgClient.signWebhook(payload);
        String signature2 = mockPgClient.signWebhook(payload);

        // Then
        assertThat(signature1).isEqualTo(signature2);
    }

    @Test
    @DisplayName("다른 payload에 대해 다른 서명 생성")
    void webhookSignature_differentPayload_differentSignature() {
        // Given
        MockPgClient mockPgClient = new MockPgClient();
        String payload1 = "{\"paymentKey\":\"test-key-1\",\"status\":\"APPROVED\"}";
        String payload2 = "{\"paymentKey\":\"test-key-2\",\"status\":\"APPROVED\"}";

        // When
        String signature1 = mockPgClient.signWebhook(payload1);
        String signature2 = mockPgClient.signWebhook(payload2);

        // Then
        assertThat(signature1).isNotEqualTo(signature2);
    }
}
