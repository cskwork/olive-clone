package com.olive.commerce.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.error.ErrorBody;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.payment.client.exception.PgTimeoutException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Payment API Controller (OLV-072).
 * <p>
 * 결제 확인 엔드포인트 구현.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    /**
     * 결제 확인 (PRD §8.4).
     *
     * @param idempotencyKey 멱등성 키 (선택 but 권장)
     * @param request         결제 확인 요청
     * @return 결제 확인 응답
     */
    @PostMapping("/confirm")
    public ResponseEntity<?> confirmPayment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentDtos.ConfirmRequest request
    ) {
        try {
            UUID key = parseIdempotencyKey(idempotencyKey);
            PaymentDtos.ConfirmResponse response = paymentService.confirmPayment(request, key);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (PgTimeoutException e) {
            log.warn("PG timeout: {}", e.getMessage());
            ErrorBody errorBody = ErrorBody.of(
                    ErrorCode.PG_TIMEOUT.name(),
                    "PG request timed out. Please retry.",
                    "/api/payments/confirm",
                    "" // traceId는 Filter에서 설정됨
            );
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ApiResponse.failure(errorBody));
        }
    }

    /**
     * Idempotency-Key 헤더 파싱.
     */
    private UUID parseIdempotencyKey(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(headerValue);
        } catch (IllegalArgumentException e) {
            // Invalid UUID는 무시하고 null 반환 (새 UUID 생성)
            log.warn("Invalid Idempotency-Key format: {}", headerValue);
            return null;
        }
    }

    /**
     * PG 웹훅 수신 (OLV-073).
     * <p>
     * PG사가 비동기로 결제 상태 변경을 푸시한다.
     * 웹훅이 진짜 결제 상태의 소스 of truth (PRD §6.6).
     *
     * @param signature 웹훅 서명 (HMAC-SHA256)
     * @param rawBody   raw 웹훅 요청 body
     * @return 항상 200 (성공/실패/중복 모두 200)
     */
    @PostMapping("/webhook")
    public ResponseEntity<ApiResponse<PaymentDtos.WebhookResponse>> handleWebhook(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody String rawBody
    ) throws Exception {
        PaymentDtos.WebhookRequest request =
                objectMapper.readValue(rawBody, PaymentDtos.WebhookRequest.class);
        PaymentDtos.WebhookResponse response = paymentService.handleWebhook(request, signature, rawBody);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
