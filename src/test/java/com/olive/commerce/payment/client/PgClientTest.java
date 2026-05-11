package com.olive.commerce.payment.client;

import com.olive.commerce.payment.client.dto.ConfirmRequest;
import com.olive.commerce.payment.client.dto.ConfirmResponse;
import com.olive.commerce.payment.client.exception.PgTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PgClient: Mock 결제 gateway 동작 검증")
class PgClientTest {

    private PgClient pgClient;

    @BeforeEach
    void setUp() {
        pgClient = new MockPgClient();
    }

    @Nested
    @DisplayName("confirmPayment: 기본 동작")
    class ConfirmPaymentDefault {

        @Test
        @DisplayName("AC1: 기본 설정으로 confirm 호출 시 <50ms 내에 APPROVED 반환")
        void testConfirmDefault_ReturnsApprovedIn50ms() {
            // Arrange
            ConfirmRequest request = new ConfirmRequest(
                "test-payment-key",
                1001L,
                new BigDecimal("15000"),
                UUID.randomUUID()
            );

            // Act
            long start = System.nanoTime();
            ConfirmResponse response = pgClient.confirmPayment(request);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // Assert
            assertThat(response.status()).isEqualTo("APPROVED");
            assertThat(response.approvedAt()).isNotNull();
            assertThat(elapsedMs).isLessThan(50);
        }
    }

    @Nested
    @DisplayName("confirmPayment: X-Mock-Pg-Behaviour 헤더 기반 동작 모드")
    class ConfirmPaymentWithBehaviour {

        @Test
        @DisplayName("AC2: X-Mock-Pg-Behaviour=fail 시 FAILED with reason=MOCK_FAIL 반환")
        void testConfirmWithFailHeader_ReturnsFailedWithMockFailReason() {
            // Arrange
            ConfirmRequest request = new ConfirmRequest(
                "test-payment-key",
                1002L,
                new BigDecimal("15000"),
                UUID.randomUUID()
            );
            MockPgClient mockClient = (MockPgClient) pgClient;
            mockClient.setBehaviour("fail");

            // Act
            ConfirmResponse response = mockClient.confirmPayment(request);

            // Assert
            assertThat(response.status()).isEqualTo("FAILED");
            assertThat(response.failedReason()).isEqualTo("MOCK_FAIL");
        }

        @Test
        @DisplayName("AC3: X-Mock-Pg-Behaviour=timeout 시 6초 대기 후 PgTimeoutException 발생")
        void testConfirmWithTimeoutHeader_Blocks6sThenThrowsPgTimeoutException() {
            // Arrange
            ConfirmRequest request = new ConfirmRequest(
                "test-payment-key",
                1003L,
                new BigDecimal("15000"),
                UUID.randomUUID()
            );
            MockPgClient mockClient = (MockPgClient) pgClient;
            mockClient.setBehaviour("timeout");

            // Act & Assert
            long start = System.nanoTime();
            assertThatThrownBy(() -> mockClient.confirmPayment(request))
                .isInstanceOf(PgTimeoutException.class)
                .hasMessageContaining("PG request timed out");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).isGreaterThanOrEqualTo(5500); // 6초 근처 (약간의 오차 허용)
        }
    }

    @Nested
    @DisplayName("confirmPayment: approve 명시 모드")
    class ConfirmPaymentWithApprove {

        @Test
        @DisplayName("X-Mock-Pg-Behaviour=approve 시 명시적 APPROVED 반환")
        void testConfirmWithApproveHeader_ReturnsApproved() {
            // Arrange
            ConfirmRequest request = new ConfirmRequest(
                "test-payment-key",
                1004L,
                new BigDecimal("15000"),
                UUID.randomUUID()
            );
            MockPgClient mockClient = (MockPgClient) pgClient;
            mockClient.setBehaviour("approve");

            // Act
            ConfirmResponse response = mockClient.confirmPayment(request);

            // Assert
            assertThat(response.status()).isEqualTo("APPROVED");
            assertThat(response.approvedAt()).isNotNull();
        }
    }
}
