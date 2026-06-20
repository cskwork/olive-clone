package com.olive.commerce.payment.client;

import com.olive.commerce.payment.client.dto.*;
import com.olive.commerce.payment.client.exception.PgTimeoutException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Mock PG 클라이언트 구현.
 * QA에서 결제 성공/실패/타임아웃 케이스를 재시작 없이 테스트할 수 있다.
 * 동작 모드는 {@link #setBehaviour(String)}으로 제어한다.
 *
 * <p>웹훅 서명용 시크릿은 생성자를 통해 주입된다. Spring 컨텍스트 외부에서
 * (예: 단위 테스트에서) no-arg 생성자로 인스턴스를 생성할 경우
 * application-test.yml 의 기본값과 동일한 값이 사용된다.
 */
public class MockPgClient implements PgClient {

    /**
     * 단위 테스트용 no-arg 생성자에서 사용하는 기본 시크릿.
     * 값은 application-test.yml 의 olive.pg.webhook-secret 과 동일해야 한다.
     */
    public static final String DEFAULT_TEST_SECRET = "mock-webhook-secret-for-testing";

    private final String webhookSecret;

    /**
     * 단위 테스트 전용 no-arg 생성자 — Spring 외부에서 직접 인스턴스화할 때 사용.
     * Spring 빈으로는 {@link #MockPgClient(String)} 이 사용된다.
     */
    public MockPgClient() {
        this(DEFAULT_TEST_SECRET);
    }

    /**
     * Spring Bean 전용 생성자.
     *
     * @param webhookSecret {@code olive.pg.webhook-secret} 설정값
     */
    public MockPgClient(String webhookSecret) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException(
                "olive.pg.webhook-secret must not be blank; configure it in application.yml");
        }
        this.webhookSecret = webhookSecret;
    }

    /**
     * 동작 모드.
     * - null 또는 "approve": APPROVED 반환 (기본)
     * - "fail": FAILED 반환 (failedReason=MOCK_FAIL)
     * - "timeout": 6초 대기 후 PgTimeoutException 발생
     */
    private volatile String behaviour = null;

    public void setBehaviour(String behaviour) {
        this.behaviour = behaviour;
    }

    /**
     * 웹훅 payload에 HMAC-SHA256 서명 생성.
     * 실제 PG사마다 서명 방식이 다르므로 이는 mock 구현.
     *
     * @param payload 웹훅 바디 JSON 문자열
     * @return Base64 인코딩된 서명
     */
    public String signWebhook(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] signedBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign webhook", e);
        }
    }

    /**
     * 웹훅 서명 검증.
     *
     * @param payload  웹훅 바디 JSON 문자열
     * @param signature 검증할 서명
     * @return 서명이 유효하면 true
     */
    public boolean verifyWebhookSignature(String payload, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String expectedSignature = signWebhook(payload);
        return MessageDigest.isEqual(
            expectedSignature.getBytes(StandardCharsets.UTF_8),
            signature.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 웹훅용 공유 시크릿 반환.
     * Spring 빈 외부(단위 테스트)에서의 시크릿 조회용.
     */
    public String getWebhookSecret() {
        return webhookSecret;
    }

    @Override
    public PaymentRequestResponse requestPayment(PaymentRequest request) {
        String paymentKey = "mock-payment-key-" + UUID.randomUUID();
        String checkoutUrl = "https://mock.pg/checkout/" + paymentKey;
        return new PaymentRequestResponse(paymentKey, checkoutUrl);
    }

    @Override
    public ConfirmResponse confirmPayment(ConfirmRequest request) {
        if ("timeout".equals(behaviour)) {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PgTimeoutException("PG request interrupted", e);
            }
            throw new PgTimeoutException("PG request timed out after 6s");
        }

        if ("fail".equals(behaviour)) {
            return new ConfirmResponse("FAILED", null, "MOCK_FAIL");
        }

        // 기본 동작 또는 "approve" 모드
        return new ConfirmResponse("APPROVED", LocalDateTime.now(), null);
    }

    @Override
    public CancelResponse cancelPayment(CancelRequest request) {
        return new CancelResponse("CANCELED", LocalDateTime.now(), request.cancelReason());
    }

    @Override
    public RefundResponse refund(RefundRequest request) {
        String pgRefundKey = "mock-refund-key-" + UUID.randomUUID();
        return new RefundResponse("REFUNDED", LocalDateTime.now(), pgRefundKey);
    }

    @Override
    public VerifyResponse verify(String paymentKey) {
        return new VerifyResponse("APPROVED", paymentKey, BigDecimal.ZERO);
    }
}
